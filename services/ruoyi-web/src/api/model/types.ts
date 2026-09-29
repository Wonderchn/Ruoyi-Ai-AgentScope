// 查询用户模型列表返回的数据结构
export interface GetSessionListVO {
  id?: number;
  category?: string;
  modelName?: string;
  providerCode?: string;
  modelDescribe?: string | null;
  modelPrice?: number;
  modelType?: string;
  modelShow?: string;
  systemPrompt?: string;
  apiHost?: string;
  remark?: string;
}
