export default function Home() {
  return (
    <main style={{ fontFamily: "system-ui", padding: 32, lineHeight: 1.7 }}>
      <h1>LANLink 商品批量导入工作流</h1>
      <p>这是一个 Vercel Workflow 服务。接口：</p>
      <ul>
        <li><code>POST /api/import</code> — 提交商品数组或 CSV，启动可恢复导入，返回 <code>runId</code></li>
        <li><code>GET /api/import/:runId</code> — 查询进度与结果</li>
      </ul>
      <p>本地观测面板：<code>npx workflow web</code></p>
    </main>
  );
}
