# Day12 Retrieval Evaluation

Generated: 2026-09-24T04:09:13.853651900Z

- Dataset: `src/test/resources/eval/retrieval-cases.tsv` (SHA-256 `7d01c8056ee24fbd6c12d922062fd9a61fb69628d4e0b7a2d6ba97dfa1aedd24`; 18 cases)
- Validation includes the four known Day12 plan queries; no parameters were tuned on validation.
- Index manifest: `target/day12/day12_eval_4535c47752e34b2ab1ba48118a6af704/index-manifest.json` (isolated schema `day12_eval_4535c47752e34b2ab1ba48118a6af704`, removed after test)
- Corpus SHA-256: `31959fe5eb6d4d8d0bc008cbdd92b4da1d6650b43a17aa0419ebb779c4c009af`; chunks: 23; splitter: `markdown-heading-budget-v2`.
- Backend: PostgreSQL 15.19, pgvector 0.7.2; embedding model `bge-m3` digest `7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab`, dimensions 1024.
- Settings: top 20 shared vector candidates, thresholds 0 (full-rank diagnostic) and 0.47 (current production setting), one warmup and three measured samples per query.
- A=VECTOR; B=HYBRID (vector + lexical RRF); C=HYBRID_RERANK. No generation LLM calls.
- Recall@1/@3: first 1/3 **chunks**, then distinct matching sources / gold source count. MRR@20: reciprocal position of first relevant source in the filtered chunk ranking, zero if absent.
- Latency: median shared candidate retrieval + median mode-specific ranking per query; the same candidate latency appears in all modes and thresholds. These are sequential local measurements, not an isolated production benchmark.

| Split | Threshold | Mode | Cases | Recall@1 | Recall@3 | MRR@20 | Candidate ms | Ranking ms | Total ms |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|
| dev | 0.0 | VECTOR | 9 | 0.8333 | 0.8889 | 1.0000 | 25.7258 | 0.0257 | 25.7515 |
| dev | 0.0 | HYBRID | 9 | 0.8333 | 0.8889 | 1.0000 | 25.7258 | 0.4368 | 26.1627 |
| dev | 0.0 | HYBRID_RERANK | 9 | 0.8333 | 0.9444 | 1.0000 | 25.7258 | 0.9117 | 26.6375 |
| dev | 0.47 | VECTOR | 9 | 0.8333 | 0.8889 | 1.0000 | 25.7258 | 0.0112 | 25.7371 |
| dev | 0.47 | HYBRID | 9 | 0.8333 | 0.8889 | 1.0000 | 25.7258 | 0.3885 | 26.1143 |
| dev | 0.47 | HYBRID_RERANK | 9 | 0.8333 | 0.9444 | 1.0000 | 25.7258 | 0.7697 | 26.4955 |
| validation | 0.0 | VECTOR | 9 | 0.8333 | 0.9444 | 0.9444 | 24.9736 | 0.0188 | 24.9925 |
| validation | 0.0 | HYBRID | 9 | 0.8333 | 0.9444 | 0.9444 | 24.9736 | 0.3853 | 25.3590 |
| validation | 0.0 | HYBRID_RERANK | 9 | 0.8889 | 0.8889 | 1.0000 | 24.9736 | 0.7981 | 25.7717 |
| validation | 0.47 | VECTOR | 9 | 0.8333 | 0.9444 | 0.9444 | 24.9736 | 0.0073 | 24.9809 |
| validation | 0.47 | HYBRID | 9 | 0.8333 | 0.9444 | 0.9444 | 24.9736 | 0.3319 | 25.3056 |
| validation | 0.47 | HYBRID_RERANK | 9 | 0.8889 | 0.8889 | 1.0000 | 24.9736 | 0.6250 | 25.5987 |

Metrics are observations; the test does not require C to outperform A or B. Reproduce with `mvn test -Dtest=RetrievalEvaluationIT` while the Day11 VM DB and BGE-M3 are available.
