// The tab's Merged section: the mvnpm bumps merged in the last days, in repos with backport labels (Quarkus'
// triage/backport-3.40 and so on), read from board/merged.json. Backport tries the bump as a cherry-pick onto the
// label's branch first; only a clean one is offered the label, and adding it waits for your click in the desk's bar.
import { LitElement, html, css, shared, ago, spinner, elf } from '/elf-kit/1/kit.js';

const LABEL = /^(?:[A-Za-z0-9 _.-]{1,40}\/)?backport(?:-[0-9][A-Za-z0-9.]{0,20})?\??$/i;

/** The backport labels a merged PR has already. */
export function backportLabels(item) {
    return (item.labels ?? []).filter(l => LABEL.test(l));
}

class BackportList extends LitElement {
    static properties = {
        filterText: { type: String },
        _merged: { state: true },
        _open: { state: true },
        // itemKey -> {label, state: 'checking'|'clean'|'conflicts'|'adding'|'added'|'error', branch, conflicts, error}
        _tries: { state: true },
        _picking: { state: true },
    };

    static styles = [shared, css`
        :host { display: block; }
        ul { list-style: none; margin: 0; padding: 0; }
        li { display: flex; gap: 12px; align-items: flex-start; padding: 10px 14px; border-top: 1px solid var(--line); }
        li:first-child { border-top: 0; }
        .main { flex: 1; min-width: 0; }
        .title { font-weight: 600; color: var(--text); text-decoration: none; }
        .title:hover { text-decoration: underline; }
        .meta { display: flex; flex-wrap: wrap; gap: 4px 10px; font-size: 13px; color: var(--muted); margin-top: 3px; align-items: center; }
        .acts { display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; align-items: center; }
        .try { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; font-size: 13px; }
        .try code { font-size: 12px; }
        .pick { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
        summary { cursor: pointer; font-weight: 600; }
        section { margin-top: 18px; }
        .hint { font-size: 13px; color: var(--muted); margin: 0 0 8px; }
    `];

    constructor() {
        super();
        this._merged = null;
        this._open = false;
        this._tries = {};
        this._picking = null;
        this.filterText = '';
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            this._merged = await elf.readJson('board', 'merged.json');
        } catch {
            this._merged = null;
        }
    }

    _matches(item) {
        const words = (this.filterText ?? '').toLowerCase().split(/\s+/).filter(Boolean);
        const text = `${item.title} ${item.repo} ${item.number} merged backport`.toLowerCase();
        return words.every(w => text.includes(w));
    }

    render() {
        const items = (this._merged?.items ?? []).filter(i => this._matches(i));
        if (!items.length) return '';
        // The ones with no backport label yet are the ones to look at.
        const open = items.filter(i => !backportLabels(i).length).length;
        return html`
            <section id="sec-merged" aria-label="Merged">
                <details ?open=${this._open || !!this.filterText} @toggle=${e => { this._open = e.target.open; }}>
                    <summary>Merged bumps, to backport? <span class="n">${open}</span></summary>
                    ${this._open || this.filterText ? html`<div class="panel">
                        <ul>${items.map(i => this._row(i))}</ul>
                    </div>` : ''}
                </details>
            </section>`;
    }

    _row(item) {
        const has = backportLabels(item);
        const t = this._tries[item.key];
        const left = (item.backport ?? []).filter(l => !has.includes(l.name));
        return html`
            <li>
                <div class="main">
                    <a class="title" href=${item.url} target="_blank" rel="noopener">${item.title}</a>
                    <div class="meta">
                        <span>${item.repo} #${item.number}</span>
                                                <span>merged ${ago(item.mergedAt)}</span>
                        ${has.map(l => html`<span class="chip ok" title="Labelled for backport">${l}</span>`)}
                    </div>
                    ${this._picking === item.key ? html`
                        <div class="pick">
                            ${left.map(l => html`<button class="sm" @click=${() => this._try(item, l)}
                                title="Try it as a cherry-pick onto ${l.branch}">${l.name} <span class="muted">→ ${l.branch}</span></button>`)}
                            <button class="sm quiet" @click=${() => { this._picking = null; }}>Cancel</button>
                        </div>` : ''}
                    ${t ? this._try_(item, t) : ''}
                </div>
                <div class="acts">
                    ${left.length && this._picking !== item.key && !['checking', 'adding'].includes(t?.state)
                        ? html`<button class="sm" @click=${() => { this._picking = item.key; }}
                            title="Pick a branch: it's tried as a cherry-pick before the label goes on">Backport…</button>` : ''}
                    ${!['checking', 'adding', 'skipping'].includes(t?.state)
                        ? html`<button class="sm quiet" @click=${() => this._skip(item)}
                            title="Takes it off this list for good; nothing changes on GitHub">${has.length ? 'Done' : "Don't backport"}</button>` : ''}
                </div>
            </li>`;
    }

    _try_(item, t) {
        switch (t.state) {
            case 'checking':
                return html`<div class="try">${spinner} Trying it as a cherry-pick onto ${t.branch}…</div>`;
            case 'clean':
                return html`<div class="try">
                    <span class="chip ok">Cherry-picks cleanly onto ${t.branch}</span>
                    <button class="sm primary" @click=${() => this._add(item, t)}>Add ${t.label}</button>
                    <button class="sm quiet" @click=${() => this._forget(item)}>Not now</button></div>`;
            case 'conflicts':
                return html`<div class="try">
                    <span class="chip bad">Conflicts on ${t.branch}</span>
                    <span>in ${t.conflicts.map((f, n) => html`${n ? ', ' : ''}<code>${f}</code>`)}: it can't be backported as is, so no label.</span>
                    <button class="sm quiet" @click=${() => this._forget(item)}>OK</button></div>`;
            case 'adding':
                return html`<div class="try">${spinner} Adding ${t.label}…</div>`;
            case 'skipping':
                return html`<div class="try">${spinner} Taking it off the list…</div>`;
            case 'added':
                return html`<div class="try"><span class="chip ok">Added ${t.label}</span></div>`;
            default:
                return html`<div class="try"><span class="error">${t.error}</span>
                    <button class="sm quiet" @click=${() => this._forget(item)}>OK</button></div>`;
        }
    }

    _set(item, t) {
        this._tries = { ...this._tries, [item.key]: t };
    }

    _forget(item) {
        const { [item.key]: _, ...rest } = this._tries;
        this._tries = rest;
    }

    async _try(item, l) {
        this._picking = null;
        this._set(item, { label: l.name, branch: l.branch, state: 'checking' });
        try {
            const c = await elf.act('backport-check', { repo: item.repo, pr: item.number, label: l.name });
            this._set(item, c.clean
                ? { label: l.name, branch: c.branch, state: c.labelled ? 'added' : 'clean' }
                : { label: l.name, branch: c.branch, state: 'conflicts', conflicts: c.conflicts ?? [] });
        } catch (e) {
            this._set(item, { label: l.name, branch: l.branch, state: 'error', error: `Couldn't try it: ${(e?.message || String(e))}` });
        }
    }

    /** Not to backport (or done with it): off the list, for good. */
    async _skip(item) {
        const was = this._tries[item.key];
        this._picking = null;
        this._set(item, { state: 'skipping' });
        try {
            await elf.act('backport-skip', { repo: item.repo, pr: item.number });
            this._forget(item);
            this._merged = { ...this._merged, items: (this._merged?.items ?? []).filter(i => i.key !== item.key) };
        } catch (e) {
            this._set(item, { ...(was ?? {}), state: 'error', error: `Couldn't take it off: ${(e?.message || String(e))}` });
        }
    }

    async _add(item, t) {
        this._set(item, { ...t, state: 'adding' });
        try {
            await elf.act('backport', { repo: item.repo, pr: item.number, label: t.label, branch: t.branch });
            this._set(item, { ...t, state: 'added' });
            await this._load();
        } catch (e) {
            if (e?.cancelled) this._set(item, { ...t, state: 'clean' });
            else this._set(item, { ...t, state: 'error', error: (e?.message || String(e)) });
        }
    }
}

customElements.define('mvnpm-backports', BackportList);
