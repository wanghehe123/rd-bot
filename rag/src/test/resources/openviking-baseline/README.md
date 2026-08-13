# WP-0 LOCAL 检索基线

32 篇短知识样本 + 对应 gold 问题。每篇正文含唯一 token `RD_WP0_*`，避免 overlap 检索误命中。

门槛由 `OpenVikingLocalRetrievalBaselineTest` 冻结：语料 20–50 篇、Recall@8 = 1.0、p95 < 50ms。
