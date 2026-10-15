// 智能体选项（用户端最小投影：id/name/description/avatar/active/executable 六字段）
// id 是雪花字符串；executable 是服务端真值表判定的"当前可否作为对话对象"。
export interface AgentVO {
  id: string;
  name: string;
  description?: string;
  avatar?: string;
  active: boolean;
  executable: boolean;
}
