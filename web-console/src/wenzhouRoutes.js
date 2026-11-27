import { Books, ChatCircleText, Sailboat, SlidersHorizontal, Users } from '@phosphor-icons/react';

export const WENZHOU_ID = 'wenzhouai';
export const WENZHOU_NAME = 'WenzhouAI';
export const WENZHOU_LABEL = '问舟AI';
export const WenzhouIcon = Sailboat;

export const wenzhouRoutes = [
  { id: 'wenzhou-players', name: '玩家权限', Icon: Users, description: '管理问舟访问权限，查看玩家的历史会话。' },
  { id: 'wenzhou-chats', name: '聊天记录', Icon: ChatCircleText, description: '查阅全服会话与问答记录。' },
  { id: 'wenzhou-knowledge', name: '知识记录', Icon: Books, description: '查看问舟保存的玩法笔记和证据来源。' },
  { id: 'wenzhou-service', name: '服务管理', Icon: SlidersHorizontal, description: '配置模型服务与额度设置。' },
];
