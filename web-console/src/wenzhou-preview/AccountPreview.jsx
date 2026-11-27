import React, {useState, useSyncExternalStore} from 'react';
import {Confirm} from '../titles/ui.jsx';
import {subscribePreview} from '@wenzhou-preview/managerPreview.js';
import {connectPreviewAccount, hasPreviewAccount} from '@wenzhou-preview/modelPreview.js';

export default function AccountPreview({toast}) {
  const connected = useSyncExternalStore(subscribePreview, hasPreviewAccount);
  const [dialog, setDialog] = useState(null);
  function finish() {
    const login = dialog === 'login';
    connectPreviewAccount(login);
    setDialog(null);
    toast(login ? '已连接演示账号' : '已断开演示账号');
  }
  return <section className="settings-section wza-account-preview">
    <div className="settings-section-heading"><h2>ChatGPT 账号</h2></div>
    <div className="setting-row"><div><strong>{connected ? '服主演示账号' : '未登录'}</strong>
      <p>{connected ? '已连接 · GPT 模型仅在服主的问舟中可选。' : '登录后，服主可以在问舟中切换 GPT 模型。'}</p></div>
      <button className={'button ' + (connected ? 'secondary' : 'primary')} onClick={() => setDialog(connected ? 'logout' : 'login')}>
        {connected ? '断开账号' : '使用 ChatGPT 登录'}
      </button>
    </div>
    {dialog && <Confirm title={dialog === 'login' ? '预览 ChatGPT 登录' : '断开演示账号？'} subtitle="问舟 AI"
      label={dialog === 'login' ? '使用演示账号' : '断开账号'} cancel={() => setDialog(null)} confirm={finish}>
      {dialog === 'login' ? '此 Web 预览使用模拟账号，无需输入密码。连接后可在 HUB 体验模型切换。' : '断开后，HUB 将切回 DeepSeek；已保存的对话会保留。'}
    </Confirm>}
  </section>;
}
