package com.wish.rd.engine.bugfix;

import org.springframework.stereotype.Service;

@Service
public class RdBotFixEngine {

    public void runBugFix() {
        // 1. chatQueueLimiter限流在这里包上

        // 2.com.wish.rd.engine.rag.RagBugFixEngine.findBugFixMessgaesForAgent(com.wish.rd.adapter.TicketSnapshot, java.util.List<java.lang.String>, boolean)， 任务状态流转到searching

        // 3. 写一个PromptBuilder类，按照固定的模板编写bugfix prompt

        // 4. bugFixExecutor 执行具体任务(只写接口，不实现) 任务流转到 executing bugFixExecutor执行完毕会返回一个bugFixMessage实体类，包括执行任务id、bug描述、解决方案、PR链接等等必要参数

        // 5. 执行完毕后任务流转到commit，等待RD审批
    }

}
