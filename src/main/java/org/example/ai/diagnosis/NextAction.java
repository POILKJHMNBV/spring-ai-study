package org.example.ai.diagnosis;

import java.util.Objects;

/**
 * 建议执行的下一步操作；当前仅表达建议，不会执行任何有副作用的动作。
 *
 * @param description 下一步建议的内容
 * @param requiresApproval 执行该建议前是否需要人工审批
 */
public record NextAction(String description, boolean requiresApproval) {

    /**
     * 创建下一步建议。
     *
     * @param description 下一步建议的内容
     * @param requiresApproval 执行该建议前是否需要人工审批
     */
    public NextAction {
        Objects.requireNonNull(description, "下一步建议不能为空");
    }
}
