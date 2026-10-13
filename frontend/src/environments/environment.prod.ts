export const environment = {
  production: true,
  apiUrl: '',
  // 留空兼容当前部署；明确父系统域名后应配置为白名单。
  allowedMessageOrigins: [] as string[]
};
