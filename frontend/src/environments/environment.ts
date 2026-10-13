export const environment = {
  production: false,
  apiUrl: 'http://localhost:12011',
  // 生产上线后建议填写父系统 origin，例如 ['http://192.168.0.10']。
  allowedMessageOrigins: [] as string[]
};
