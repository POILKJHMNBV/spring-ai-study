package org.example.ai.approval;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Day16 提案、可信人工审核、模拟执行与审计服务。
 *
 * <p>所有状态保存在当前 JVM 的内存中，应用重启会丢失。人工入口是受信任宿主代码直接调用本服务；
 * 传入的操作身份由该宿主负责确认，本类本身不认证身份，也不提供 HTTP 接口。此服务不能在 Day17
 * RBAC/身份认证完成前直接暴露给外部用户。审批与执行方法没有注册成 Agent Tool，模型只能创建提案。</p>
 *
 * <p>每个提案的审批与执行转换都在其状态对象监视器内完成。同一提案的并发执行请求会串行化，
 * 只有第一次从 APPROVED 转换到执行态的请求会调用执行器。执行器异常进入终态，避免在外部副作用
 * 是否已经发生不确定时重试。</p>
 */
@Service
public class ApprovalService {
    /**
     * 服务端 Topic 白名单；与提案工具收到的模型参数独立再次校验。
     */
    private static final Set<String> ALLOWED_TOPICS = Set.of("order-topic");
    /**
     * 扩容动作允许的最小副本数，防止非法的小值被当成有效目标。
     */
    private static final int MIN_REPLICAS = 2;
    /**
     * 扩容动作允许的最大副本数，用固定上界限制误操作范围。
     */
    private static final int MAX_REPLICAS = 10;
    /**
     * 提案原因上限，避免长文本占用审计和工具上下文。
     */
    private static final int MAX_REASON_LENGTH = 300;
    /**
     * 身份标识上限，避免把任意长调用方文本写进审计记录。
     */
    private static final int MAX_ACTOR_LENGTH = 100;

    /**
     * 进程内提案表；单项状态转换由对应 ProposalState 锁保护。
     */
    private final ConcurrentMap<String, ProposalState> proposals = new ConcurrentHashMap<>();
    /**
     * 进程内追加式审计序列，查询时总是返回不可变副本。
     */
    private final CopyOnWriteArrayList<ApprovalAuditRecord> audit = new CopyOnWriteArrayList<>();
    /**
     * 唯一受信执行边界；生产装配下该依赖只能解析到模拟执行器。
     */
    private final ActionExecutor executor;

    /**
     * 创建审批服务并绑定 Spring 配置的动作执行器。
     *
     * @param executor Day16 注册的模拟动作执行器；不得将真实写操作实现注入此服务
     */
    public ApprovalService(ActionExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "执行器不能为空");
    }

    /**
     * 校验并创建 Consumer 扩容提案，或者复用完全相同的待审批提案。
     *
     * <p>Topic、副本数和原因都由服务端验证。动作、风险等级及审批要求不是调用参数：动作固定为
     * SCALE_CONSUMER，风险固定为 MEDIUM，approvalRequired 固定为 true。</p>
     *
     * @param topic    模型建议的 Kafka Topic；必须属于服务端 Topic 白名单
     * @param replicas 模型建议的目标副本数；必须在 2 到 10 之间
     * @param reason   模型给出的提案原因；去除首尾空白后为 1 到 300 个字符且不包含控制字符
     * @return 不可变提案；完全相同且仍待审批的提案将返回同一 ID
     * @throws IllegalArgumentException 当参数不符合服务端动作策略时抛出
     */
    public synchronized ActionProposal proposeScaleConsumer(String topic, int replicas, String reason) {
        String actor = "agent";
        try {
            validateProposal(topic, replicas, reason);
        } catch (IllegalArgumentException exception) {
            appendAudit("<proposal-rejected>", ApprovalAuditEvent.PROPOSAL_ATTEMPT_REJECTED, actor,
                    "proposal rejected by server-side action policy");
            throw exception;
        }

        String normalizedReason = reason.trim();
        Optional<ProposalState> existing = proposals.values().stream()
                .filter(state -> state.status == ProposalStatus.PENDING_APPROVAL)
                .filter(state -> state.proposal.action() == ActionType.SCALE_CONSUMER
                        && state.proposal.target().equals(topic)
                        && state.proposal.replicas() == replicas
                        && state.proposal.reason().equals(normalizedReason))
                .findFirst();
        if (existing.isPresent()) {
            ActionProposal reused = existing.get().proposal;
            appendAudit(reused.id(), ApprovalAuditEvent.DEDUPLICATED, actor,
                    "reused identical pending proposal");
            return reused;
        }

        ActionProposal proposal = new ActionProposal(
                UUID.randomUUID().toString(),
                ActionType.SCALE_CONSUMER,
                topic,
                replicas,
                normalizedReason,
                ActionRisk.MEDIUM,
                true,
                Instant.now()
        );
        proposals.put(proposal.id(), new ProposalState(proposal));
        appendAudit(proposal.id(), ApprovalAuditEvent.PROPOSED, actor,
                "pending human approval; no action executed");
        return proposal;
    }

    /**
     * 列出当前仍等待审批的不可变提案快照。
     *
     * @return 按创建时间排序且不可修改的待审批提案列表
     */
    public List<ActionProposal> findPendingProposals() {
        // 每项状态通过对应锁读取。并发审批可能发生在两条提案读取之间，因此这是弱一致快照；
        // 单条记录本身始终对应一个完整不可变提案，不会把 proposal 内容与状态字段混合返回。
        List<ActionProposal> pending = new ArrayList<>();
        for (ProposalState state : proposals.values()) {
            synchronized (state) {
                if (state.status == ProposalStatus.PENDING_APPROVAL) {
                    pending.add(state.proposal);
                }
            }
        }
        return pending.stream()
                .sorted(Comparator.comparing(ActionProposal::createdAt).thenComparing(ActionProposal::id))
                .toList();
    }

    /**
     * 按提案 ID 查找一个不可变提案快照，供可信应用侧人工查看完整提案内容。
     *
     * @param proposalId 服务端生成的提案 ID
     * @return 找到提案时返回其快照；状态可通过审计记录追踪
     */
    public Optional<ActionProposal> findProposal(String proposalId) {
        if (proposalId == null) {
            return Optional.empty();
        }
        ProposalState state = proposals.get(proposalId);
        return state == null ? Optional.empty() : Optional.of(state.proposal);
    }

    /**
     * 将一个待审批提案批准为 APPROVED。
     *
     * <p>本方法只供可信应用侧操作代码调用，不是身份认证机制。模型没有对应 Tool，也不能通过
     * 提案参数指定 approverId。</p>
     *
     * @param proposalId 服务端生成的提案 ID
     * @param approverId 由可信宿主确认的操作人员标识
     * @return 已记录并审计的审批结果
     * @throws ApprovalException 提案不存在、身份无效或提案不处于待审批状态时抛出；拒绝尝试也会审计
     */
    public ApprovalDecisionResult approve(String proposalId, String approverId) {
        ProposalState state = lookupForReview(proposalId, approverId, ApprovalAuditEvent.APPROVAL_ATTEMPT_REJECTED);
        synchronized (state) {
            approverId = validateActorOrReject(proposalId, approverId,
                    ApprovalAuditEvent.APPROVAL_ATTEMPT_REJECTED);
            if (state.status != ProposalStatus.PENDING_APPROVAL) {
                rejectAttempt(proposalId, approverId, ApprovalAuditEvent.APPROVAL_ATTEMPT_REJECTED,
                        "proposal is not pending approval");
            }
            state.status = ProposalStatus.APPROVED;
            Instant now = Instant.now();
            appendAudit(state.proposal.id(), ApprovalAuditEvent.APPROVED, approverId,
                    "trusted application-side approval");
            return new ApprovalDecisionResult(state.proposal.id(), state.status, approverId, now);
        }
    }

    /**
     * 将一个待审批提案拒绝为 REJECTED；拒绝状态不可恢复，也不能执行。
     *
     * @param proposalId 服务端生成的提案 ID
     * @param approverId 由可信宿主确认的操作人员标识
     * @param reason     人工拒绝理由；限长并且不包含控制字符
     * @return 已记录并审计的拒绝结果
     * @throws ApprovalException 提案不存在、身份或理由无效、或提案不处于待审批状态时抛出
     */
    public ApprovalDecisionResult reject(String proposalId, String approverId, String reason) {
        ProposalState state = lookupForReview(proposalId, approverId, ApprovalAuditEvent.APPROVAL_ATTEMPT_REJECTED);
        synchronized (state) {
            approverId = validateActorOrReject(proposalId, approverId,
                    ApprovalAuditEvent.APPROVAL_ATTEMPT_REJECTED);
            String normalizedReason;
            try {
                normalizedReason = validateHumanText(reason, MAX_REASON_LENGTH, "拒绝理由");
            } catch (IllegalArgumentException exception) {
                appendAudit(bounded(proposalId, 64), ApprovalAuditEvent.APPROVAL_ATTEMPT_REJECTED,
                        approverId, "invalid rejection reason");
                throw new ApprovalException("拒绝理由无效", exception);
            }
            if (state.status != ProposalStatus.PENDING_APPROVAL) {
                rejectAttempt(proposalId, approverId, ApprovalAuditEvent.APPROVAL_ATTEMPT_REJECTED,
                        "proposal is not pending approval");
            }
            state.status = ProposalStatus.REJECTED;
            Instant now = Instant.now();
            appendAudit(state.proposal.id(), ApprovalAuditEvent.REJECTED, approverId,
                    "rejected: " + normalizedReason);
            return new ApprovalDecisionResult(state.proposal.id(), state.status, approverId, now);
        }
    }

    /**
     * 执行一条已批准提案，且通过提案级同步锁保证同一 ID 最多调用执行器一次。
     *
     * <p>拒绝、未审批、未知 ID、已执行或失败终态均不会进入执行器。若执行器抛错，状态转为
     * EXECUTION_FAILED 并审计，禁止重试，因为调用方无法判断外部副作用是否已发生。</p>
     *
     * @param proposalId 已批准提案 ID
     * @param executorId 由可信宿主确认的操作人员标识
     * @return 模拟执行结果
     * @throws ApprovalException 未获批准、ID 或身份无效、已执行或执行失败时抛出
     */
    public ActionExecutionResult executeApproved(String proposalId, String executorId) {
        ProposalState state = lookupForReview(proposalId, executorId,
                ApprovalAuditEvent.EXECUTION_ATTEMPT_REJECTED);
        synchronized (state) {
            executorId = validateActorOrReject(proposalId, executorId,
                    ApprovalAuditEvent.EXECUTION_ATTEMPT_REJECTED);
            if (state.status != ProposalStatus.APPROVED) {
                rejectAttempt(proposalId, executorId, ApprovalAuditEvent.EXECUTION_ATTEMPT_REJECTED,
                        "proposal is not approved or is already terminal");
            }

            state.status = ProposalStatus.EXECUTING;
            try {
                ActionExecutionResult result = executor.execute(state.proposal);
                if (result == null || !state.proposal.id().equals(result.proposalId())
                        || result.status() != ActionExecutionStatus.SIMULATED_EXECUTION) {
                    throw new IllegalStateException("Day16 执行器必须返回匹配提案 ID 的模拟执行结果");
                }
                state.status = ProposalStatus.EXECUTED;
                appendAudit(state.proposal.id(), ApprovalAuditEvent.EXECUTED, executorId,
                        ActionExecutionStatus.SIMULATED_EXECUTION.name());
                return result;
            } catch (RuntimeException exception) {
                state.status = ProposalStatus.EXECUTION_FAILED;
                appendAudit(state.proposal.id(), ApprovalAuditEvent.EXECUTION_FAILED, executorId,
                        "executor failed; retry permanently disabled");
                throw new ApprovalException("执行失败；提案已进入不可重试的终态", exception);
            }
        }
    }

    /**
     * 返回当前进程中的审批事件不可变快照。
     *
     * @return 按追加顺序排列的审计记录
     */
    public List<ApprovalAuditRecord> auditRecords() {
        return List.copyOf(audit);
    }

    /**
     * 查找待处理提案并验证调用身份；所有失败尝试都写入通用拒绝事件。
     *
     * @param proposalId    待处理提案 ID
     * @param actor         应用侧操作主体
     * @param rejectedEvent 应记录的拒绝事件类型
     * @return 找到的提案状态对象
     */
    private ProposalState lookupForReview(String proposalId, String actor, ApprovalAuditEvent rejectedEvent) {
        if (proposalId == null || proposalId.isBlank() || proposalId.length() > 64) {
            rejectAttempt(proposalId, actor, rejectedEvent, "invalid proposal identifier");
        }
        ProposalState state = proposals.get(proposalId);
        if (state == null) {
            rejectAttempt(proposalId, actor, rejectedEvent, "unknown proposal identifier");
        }
        validateActorOrReject(proposalId, actor, rejectedEvent);
        return state;
    }

    /**
     * 校验可信宿主传入的操作身份，并对无效身份尝试留下审计事件。
     *
     * @param proposalId 涉及的提案 ID
     * @param actor      应用侧操作主体
     * @param event      无效身份对应的拒绝事件类型
     */
    private String validateActorOrReject(String proposalId, String actor, ApprovalAuditEvent event) {
        try {
            return validateHumanText(actor, MAX_ACTOR_LENGTH, "操作身份");
        } catch (IllegalArgumentException exception) {
            appendAudit(bounded(proposalId == null ? "<null>" : proposalId, 64), event,
                    "INVALID_ACTOR", "invalid application-side actor");
            throw new ApprovalException("操作身份无效", exception);
        }
    }

    /**
     * 记录一次被拒绝的审核或执行尝试，再以受控异常终止操作。
     *
     * @param proposalId 原始提案标识；审计前会被截断，避免任意长输入膨胀记录
     * @param actor      提交者；非法身份将使用固定标记
     * @param event      审核或执行拒绝事件
     * @param detail     不包含原始请求正文的固定拒绝原因
     */
    private void rejectAttempt(String proposalId, String actor, ApprovalAuditEvent event, String detail) {
        appendAudit(bounded(proposalId == null ? "<null>" : proposalId, 64), event,
                validActor(actor) ? actor.trim() : "INVALID_ACTOR", detail);
        throw new ApprovalException("审批或执行请求被服务端策略拒绝: " + detail);
    }

    /**
     * 校验扩容参数，不读取模型声明的风险或审批要求。
     *
     * @param topic    Topic 名称
     * @param replicas 目标副本数
     * @param reason   提案原因
     */
    private static void validateProposal(String topic, int replicas, String reason) {
        if (topic == null || !ALLOWED_TOPICS.contains(topic)) {
            throw new IllegalArgumentException("目标 Topic 不在服务端白名单中");
        }
        if (replicas < MIN_REPLICAS || replicas > MAX_REPLICAS) {
            throw new IllegalArgumentException("Consumer 副本数必须在 2 到 10 之间");
        }
        validateHumanText(reason, MAX_REASON_LENGTH, "提案原因");
    }

    /**
     * 校验并归一化人类可读文本，拒绝空白、超长和控制字符输入。
     *
     * @param value     待检查文本
     * @param maxLength 最大字符数
     * @param fieldName 用于固定错误信息的字段名称
     * @return 去除首尾空格后的文本
     */
    private static String validateHumanText(String value, int maxLength, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + "长度或字符格式无效");
        }
        return normalized;
    }

    /**
     * 判断身份文本是否符合审计记录限制。
     *
     * @param actor 待检查身份
     * @return 有效时返回 true
     */
    private static boolean validActor(String actor) {
        try {
            validateHumanText(actor, MAX_ACTOR_LENGTH, "操作身份");
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 将不可信定位值截断到上限，供失败审计事件使用。
     *
     * @param value 原始定位值
     * @param limit 保留字符上限
     * @return 已截断定位值
     */
    private static String bounded(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * 清理审计索引值中的控制字符并截断长度，防止未知 ID 或调用方文本伪造多行记录。
     *
     * @param value 需要作为审计索引保存的值
     * @param limit 最终字符上限
     * @return 移除控制字符且长度受限的审计字段
     */
    private static String safeAuditValue(String value, int limit) {
        StringBuilder safe = new StringBuilder(Math.min(value.length(), limit));
        for (int index = 0; index < value.length() && safe.length() < limit; index++) {
            char character = value.charAt(index);
            if (!Character.isISOControl(character)) {
                safe.append(character);
            }
        }
        return safe.toString();
    }

    /**
     * 原子地追加带服务器时间的审计记录。
     *
     * @param proposalId 提案 ID 或受控失败定位值
     * @param event      事件类型
     * @param actor      操作主体
     * @param detail     固定简短描述
     */
    private void appendAudit(String proposalId, ApprovalAuditEvent event, String actor, String detail) {
        audit.add(new ApprovalAuditRecord(
                safeAuditValue(proposalId, 64),
                event,
                safeAuditValue(actor, MAX_ACTOR_LENGTH),
                Instant.now(),
                safeAuditValue(detail, 400)
        ));
    }

    /**
     * 单条提案的可变状态容器；提案本身保持不可变，所有状态访问位于本对象同步块内。
     */
    private static final class ProposalState {
        /**
         * 创建时固定的不可变提案内容。
         */
        private final ActionProposal proposal;
        /**
         * 由审批服务在同步块内修改的流程状态。
         */
        private volatile ProposalStatus status = ProposalStatus.PENDING_APPROVAL;

        /**
         * 建立新提案的初始待审批状态。
         *
         * @param proposal 已通过服务端策略校验的不可变提案
         */
        private ProposalState(ActionProposal proposal) {
            this.proposal = proposal;
        }
    }

    /**
     * 服务端拒绝提案、审批或执行调用时抛出的受控异常。
     */
    public static final class ApprovalException extends IllegalStateException {
        /**
         * 创建不带底层细节的受控拒绝异常。
         *
         * @param message 面向调用方的固定说明
         */
        public ApprovalException(String message) {
            super(message);
        }

        /**
         * 创建执行失败异常，保留异常链供可信宿主诊断。
         *
         * @param message 固定失败说明
         * @param cause   执行器或结果校验的底层异常
         */
        public ApprovalException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
