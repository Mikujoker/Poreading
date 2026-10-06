# 实战复盘：轻小说文库（wenku8）v5 → v11

一个 CF 站的书源从"全错"到"四组规则端上全过"的完整过程。**每个症状都可能指向完全不同的根因**，这份记录用于诊断同类站点。

## 症状 → 根因 → 修法

| 症状（端上事件） | 真根因 | 修法 |
|---|---|---|
| `书籍总数:1`、`获取书名 └请稍候…`、`tocUrl=http://localhost/` | searchUrl 是 POST → `AnalyzeUrl` 的 POST 分支**先用 OkHttp 发一次** → CF 发挑战页 → 挑战页 HTML 被当详情页解析 | 搜索改 **GET**（走 WebView）。GET 不执行搜索时也不能用 POST，见下一行 |
| 搜索拿到的是"轻小说列表"首页而不是结果 | `GET /so.php?...&searchtype=articlename` 在这个站不执行搜索 | 用 `/modules/article/search.php?searchkey=<GBK转义>&page={{page}}`（并去掉 `searchtype`） |
| 结果页回 `“鏂囧∨皯濂”搜索结果`（空结果） | 站点无视 `charset` 参数、总按 GBK 解；而 WebView 取页时强制把 URL 里的中文按 UTF-8 编码（`charset` 选项只对 OkHttp 生效） | 在 searchUrl 里用 `{{...}}` 内联 JS 把关键词预编成 GBK 百分号转义 |
| 搜"文学少女"出 9 本、偏偏没有它 | 该关键词**只命中一篇文章** → 站点 302 到 `/book/1.htm`，返回的 HTML 是详情页；宽选择器把详情页里"同类小说推荐"当成了搜索结果 | `bookList` 只匹配结果表格 `div[style*='width:373px']`（实测：结果页 20 行 / 详情页 0 行）；列表为空时 Legado 会按详情页解析兜底，正好得到那一本 |
| 搜索结果书名被截断 | 行内显示文本是截断的 | `name` 用 `b>a@tiptitle`（完整书名在属性里） |
| 封面时好时坏、文学少女用默认封面 | `img[src*='img.wenku8.com']@src` 在详情页命中 10 张（本书 + 同类推荐）→ 拼成多行字符串 → 无效 URL | 限定在信息块内：`#content div[style*='99%'] img[src*='img.wenku8.com']@src`（实测两边镜像页都只命中 1 张） |
| 反复要求登录、jar/db 层 cookie 是空串 | `SourceLoginViewModel.saveCookie()` / `BackstageWebView.setCookie()` 把 `getCookie()` 的 null 结果写成空串，覆盖了有效会话；且其中的读取跑在无 Looper 的 IO 线程上 | 三处挡空值 + 读取移到主线程（app 侧修复） |
| `loginCheckJs` 一加就把搜索打挂 | `result` 是响应对象、`body` 是方法；脚本返回值被硬转 `as StrResponse`；`.contains` 只有 Java String 有、`.includes` 只有 JS String 有 | ① 硬转改宽容转换；② 用双端都有的 `indexOf`；③ 约定：返回响应对象=已登录，返回 false/null=未登录 |
| 点「登录」永远看到账号密码框 | `loginUrl` 指向 `login.php`（登录表单页），已登录时不跳转；且原 ✓ 按钮不做任何校验 | 登录页打开/按 ✓ 时用 `AnalyzeUrl` 真跑一次 `loginCheckJs` 判登录态，已登录立刻退出 |

## 最终可用源（`project/book-sources/wenku8.net.webview.json` v11）

```json
{
  "bookSourceUrl": "https://www.wenku8.net/index.php",
  "loginUrl": "https://www.wenku8.net/login.php",
  "loginCheckJs": "result.body().indexOf('退出登录') >= 0 ? result : false",
  "searchUrl": "/modules/article/search.php?searchkey={{GBK预编码(key)}}&page={{page}},{\"charset\":\"gbk\",\"webView\":true,\"webViewDelayTime\":3000}",
  "ruleSearch": {
    "bookList": "div[style*='width:373px']",
    "name": "b>a@tiptitle",
    "bookUrl": "b>a@href@js:result + ',{\"webView\":true,\"webViewDelayTime\":3000}'"
  },
  "ruleBookInfo": {
    "name": "title@text##\\s*-.*",
    "author": "td:contains(小说作者)@text##小说作者：",
    "coverUrl": "#content div[style*='99%'] img[src*='img.wenku8.com']@src",
    "tocUrl": "a:contains(小说目录)@href@js:result + ',{\"webView\":true,\"webViewDelayTime\":3000}'"
  },
  "ruleToc": { "chapterList": "td.ccss a", "chapterName": "text", "chapterUrl": "href@js:result + ',…'" },
  "ruleContent": { "content": "#content@html" }
}
```

端上实测：搜索 9 本 / 详情全字段 / 目录 213 章（文学少女）/ 正文非空 / 封面唯一可下载。

## 方法论教训

1. **判据（assertion）先校准**再驱动 AI：本轮判据曾漏剥日志行首 `└`，导致 AI 三轮全被误判为"0 个封面"而空转。
2. **挑战页/登录页必须先挡**：否则 `书名="Just a moment..."` 会被当作解析成功。
3. **别把 PC 侧结论当端上结论**：CF 站 PC 侧全 403，端上 WebView 全 200。
4. **同一症状多种根因**：本表 10 行里，有 4 行的表象都是"搜不到/结果不对"。
