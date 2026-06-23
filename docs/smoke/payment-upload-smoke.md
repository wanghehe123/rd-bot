# 上传验收文档

RustFS 上传后的文档需要进入 Parser、Chunker 和 Indexer。

OrderService.create 在创建订单前必须校验 orders.amount。

如果 orders.amount 为空，应该返回明确的参数错误，而不是继续写入订单。
