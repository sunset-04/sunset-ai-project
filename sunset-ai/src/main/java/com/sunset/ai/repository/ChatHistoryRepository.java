package com.sunset.ai.repository;

import java.util.List;

public interface ChatHistoryRepository {

    /**
     * 保存会话记录
     * @param type 业务类型,如:chat,service,pdf
     * @param chatId 会话Id
     */
    void save(String type, String chatId);

    /**
     * 会话Id列表
     * @param type 业务类型,如:chat,service,pdf
     * @return 会话Id列表
     */
    List<String> getChatIds(String type);
}
