# ADK chat diagram assets

ADK 1.10.1's bundled UI renders Mermaid fences as code. `AdkChatPage` serves the
original classpath HTML with local script/style references; the Angular bundle
is unchanged. `static/chat/diagrams.js` observes ADK Markdown blocks, including
history and streaming replacements, and adds previews beside the original source.

Mermaid 11.17.2 is pinned in `package-lock.json`. The committed browser bundle is
rebuilt from the locked dependency graph (including patched transitive packages),
not copied from Mermaid's precompiled distribution. Java startup needs no Node or
network access. To update the bundle with Node 22+ and npm:

```sh
cd scripts/chat-assets
npm ci --ignore-scripts
npm audit
npm run build
```

Commit the lockfile, `static/chat/vendor/mermaid.js`, its generated legal notices,
and license together. Rebuild and run the browser check when upgrading ADK too:
the integration deliberately depends on ADK's `markdown pre code.language-mermaid`
DOM contract and `browser/index.html` resource.

Rendering uses Mermaid's [strict security mode](https://mermaid.js.org/config/usage),
rejects configuration directives/frontmatter, bounds source size/edge count, and
serializes calls to its shared renderer. SVG is displayed as a blob-backed image,
so scripts and interactive SVG links cannot run. No diagram text leaves the browser.
The root-level `htmlLabels: false` setting is required: the deprecated
`flowchart.htmlLabels` setting is overridden in this Mermaid version. SVG text labels
support `<br/>` line breaks without emitting HTML void tags that break SVG image
decoding. The browser regression includes the reported shortener architecture with
multiline labels and validates both XML and decoded image output.
Invalid syntax retains visible source and a plain-text error; ordinary code stays
unchanged. Original source is never silently repaired. Object URLs are revoked
when their chat nodes are removed or replaced.

With the factory running, execute the regression check (requires puppeteer-core
and Chrome; paths can be overridden):

```sh
FACTORY_BROWSER_MODULE=/absolute/path/to/puppeteer-core/lib/puppeteer/puppeteer-core.js \
  node scripts/checks/adk_chat_smoke.cjs
```

Run that command from the repository root. `CHROME_PATH` overrides the executable;
`FACTORY_TEST_URL` overrides `http://localhost:8000`. It intercepts model requests
with fixture responses and session history; it never calls a paid provider.
