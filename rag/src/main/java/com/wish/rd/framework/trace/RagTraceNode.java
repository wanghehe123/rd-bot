package com.wish.rd.framework.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RAG 链路追踪节点标记注解。
 *
 * <p>标注在方法或类上，表示该方法/类应作为一个追踪节点被记录到 {@link com.wish.rd.rag.trace.RagTraceStore}。
 * 典型用法见 {@link com.wish.rd.rag.pipeline.RepairRagPipeline#prepareContext} 与
 * {@link com.wish.rd.rag.retrieval.MultiChannelRetrievalEngine#retrieve}。
 *
 * <p>运行时保留（{@link RetentionPolicy#RUNTIME}），可被测试通道显式读取或由 AOP 织入。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RagTraceNode {

    /** 节点标识值，用于在追踪记录中命名该节点。 */
    String value();

    /** 节点分类（如 rag、ingestion），便于按域筛选。 */
    String category() default "";
}
