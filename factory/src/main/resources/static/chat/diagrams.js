/* ADK's Angular bundle owns the Markdown nodes. Add sibling previews without rewriting them. */
(() => {
  'use strict';
  const selector = 'markdown pre code.language-mermaid, [markdown] pre code.language-mermaid';
  const states = new Map();
  let timer, sequence = 0, pending = Promise.resolve();
  const mermaid = globalThis.FactoryMermaid?.default;
  mermaid?.initialize({
    startOnLoad: false, securityLevel: 'strict', theme: 'default',
    suppressErrorRendering: true, maxTextSize: 20000, maxEdges: 300,
    // Mermaid 11.17 uses the root setting; flowchart.htmlLabels no longer overrides it.
    // SVG text labels preserve line breaks without HTML <br> breaking image XML decoding.
    htmlLabels: false,
    secure: ['secure', 'securityLevel', 'startOnLoad', 'maxTextSize', 'maxEdges',
      'suppressErrorRendering', 'htmlLabels'],
  });

  function dispose(state) {
    if (state.url) URL.revokeObjectURL(state.url);
    state.panel.remove();
    state.pre.classList.remove('factory-diagram-source-hidden');
  }

  async function render(code, state, source) {
    if (!code.isConnected || states.get(code) !== state) return;
    const stage = document.createElement('div');
    stage.className = 'factory-diagram-stage';
    try {
      if (!mermaid) throw new Error('The local diagram renderer could not load. Refresh the page to retry.');
      if (source.length > 20000) throw new Error('This diagram exceeds the 20,000 character display limit.');
      if (/%%\{|^\s*---/m.test(source)) throw new Error('Diagram configuration directives are disabled in chat.');
      document.body.append(stage);
      const {svg} = await mermaid.render('factory-diagram-' + (++sequence), source, stage);
      if (!code.isConnected || states.get(code) !== state || code.textContent.trim() !== source) return;
      state.url = URL.createObjectURL(new Blob([svg], {type: 'image/svg+xml'}));
      const image = document.createElement('img');
      const dimensions = new DOMParser().parseFromString(svg, 'image/svg+xml')
        .documentElement.getAttribute('viewBox')?.split(/[\s,]+/).map(Number);
      if (dimensions?.length === 4 && dimensions[2] > 0 && dimensions[3] > 0) {
        image.width = Math.ceil(dimensions[2]);
        image.height = Math.ceil(dimensions[3]);
      }
      image.alt = 'Chat diagram. Use Show source to read its Mermaid description.';
      image.src = state.url; // An image document cannot execute SVG scripts or interactive links.
      const viewport = document.createElement('div');
      viewport.className = 'factory-diagram-viewport';
      viewport.append(image);
      const toggle = document.createElement('button');
      toggle.type = 'button';
      toggle.textContent = 'Show source';
      toggle.setAttribute('aria-expanded', 'false');
      toggle.addEventListener('click', () => {
        const hidden = state.pre.classList.toggle('factory-diagram-source-hidden');
        toggle.textContent = hidden ? 'Show source' : 'Hide source';
        toggle.setAttribute('aria-expanded', String(!hidden));
      });
      image.addEventListener('error', () => {
        URL.revokeObjectURL(state.url);
        state.url = null;
        state.pre.classList.remove('factory-diagram-source-hidden');
        state.panel.classList.add('factory-diagram-error');
        state.panel.textContent = 'Unable to display this diagram. Mermaid source is shown below.';
      }, {once: true});
      state.panel.replaceChildren(viewport, toggle);
      state.pre.classList.add('factory-diagram-source-hidden');
    } catch (error) {
      if (states.get(code) !== state) return;
      state.panel.classList.add('factory-diagram-error');
      state.panel.textContent = 'Unable to render this diagram. Mermaid source is shown below. '
        + (error.message?.startsWith('The local') || error.message?.startsWith('This diagram')
          || error.message?.startsWith('Diagram configuration') ? error.message
          : 'Check the Mermaid syntax (for example, use end without a trailing quote).');
    } finally {
      stage.remove();
    }
  }

  function scan() {
    for (const [code, state] of states) {
      if (!code.isConnected) { dispose(state); states.delete(code); }
    }
    for (const code of document.querySelectorAll(selector)) {
      const source = code.textContent.trim();
      const previous = states.get(code);
      if (previous?.source === source && previous.panel.isConnected) continue;
      if (previous) dispose(previous);
      const pre = code.closest('pre');
      const panel = document.createElement('figure');
      panel.className = 'factory-diagram';
      panel.setAttribute('aria-label', 'Mermaid diagram');
      panel.textContent = 'Rendering diagram…';
      pre.before(panel);
      const state = {source, pre, panel, url: null};
      states.set(code, state);
      // Mermaid has shared configuration and render state; serialize diagram renders.
      pending = pending.then(() => render(code, state, source));
    }
  }

  const observer = new MutationObserver(records => {
    if (!records.some(record => {
      const element = record.target.nodeType === Node.ELEMENT_NODE ? record.target : record.target.parentElement;
      return !element?.closest('.factory-diagram, .factory-diagram-stage');
    })) return;
    clearTimeout(timer);
    timer = setTimeout(scan, 200); // Debounce partial/streaming Markdown updates.
  });
  observer.observe(document.body, {childList: true, subtree: true, characterData: true});
  scan();
})();
