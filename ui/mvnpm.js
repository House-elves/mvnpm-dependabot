// mvnpm-dependabot's tab on Elf Desk, and its dashboard card: Dependabot's open mvnpm bumps in the repos it checks,
// each with the elf's verdict, its CI, whose move it is and the next step, as the GitHub board's mvnpm lane drew them.
// It reads board/board.json (the elf's board command writes it) and state/running.json (the check running now).
// Checking runs the elf's commands; approving CI, merging and asking Dependabot to rebase are its actions, which
// wait for your click in the desk's bar before anything is written to GitHub.
import {
    LitElement, html, css, svg, shared, cardStyles, spinner, ago, count, settingsButton, Ticking, elf,
} from '/elf-kit/1/kit.js';

/** The tab's sections, by whose move it is (the board's flow.waiting). */
const SECTIONS = [
    { id: 'you', title: 'Your move', empty: 'Nothing needs you right now.' },
    { id: 'agent', title: 'Being checked' },
    { id: 'ci', title: 'Waiting on CI' },
    { id: 'others', title: 'Waiting on others' },
];

/** The commands that check bumps: one runs at a time. */
const CHECKS = ['round', 'check', 'dry-run'];

const VERDICTS = {
    SAFE: { cls: 'ok', text: 'Safe' },
    NOT_SAFE: { cls: 'bad', text: 'Not safe' },
    NEEDS_HUMAN: { cls: 'warn', text: 'Needs a look' },
};

async function board() {
    return elf.readJson('board', 'board.json');
}

async function running() {
    try {
        return await elf.readJson('state', 'running.json');
    } catch {
        return null;
    }
}

/** owner/repo#123 */
function key(pr) {
    return `${pr.repo}#${pr.number}`;
}

/** quarkus#123: the repo's name, which is what the check command takes. */
function shortKey(repo, number) {
    return `${repo.split('/')[1]}#${number}`;
}

/** What a check that runs now looks at, in a few words. */
function checkingText(r) {
    if (!r) return 'Checking';
    if (r.kind === 'check') return `Checking ${r.prs.map(n => shortKey(r.repo, n)).join(', ')}`;
    if (r.kind === 'dry-run') return 'Checking every open bump without commenting';
    return 'Checking every open bump';
}

/** The PR icon, coloured by CI, as the GitHub board draws it. */
function prIcon(pr) {
    const cls = { SUCCESS: 'ok', FAILURE: 'bad', ERROR: 'bad', PENDING: 'run', EXPECTED: 'run' }[pr.ci] ?? 'idle';
    return html`<svg class="icon ${cls}" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"
                    stroke-linecap="round" role="img" aria-label="Pull request">
        ${svg`<circle cx="4" cy="3.5" r="1.8"/><circle cx="4" cy="12.5" r="1.8"/><circle cx="12" cy="12.5" r="1.8"/>
              <path d="M4 5.3v5.4M12 10.7V6.5a2 2 0 0 0-2-2H7.5M9 2.8 7.3 4.5 9 6.2"/>`}
    </svg>`;
}

/** CI as a chip: what the icon's colour can't say on its own. */
function ciChip(pr) {
    switch (pr.ci) {
        case 'SUCCESS': return pr.mergeState === 'CLEAN' || pr.mergeState === 'HAS_HOOKS'
            ? html`<span class="chip ok">CI passed</span>`
            : html`<span class="chip run" title="GitHub says required checks haven't all passed yet">Waiting for required checks</span>`;
        case 'FAILURE': case 'ERROR': return html`<span class="chip bad">CI failing</span>`;
        case 'PENDING': case 'EXPECTED': return html`<span class="chip run">${spinner}CI in progress</span>`;
        case 'AWAITING_APPROVAL': return html`<span class="chip warn">CI needs approval</span>`;
        default: return html`<span class="chip">No CI yet</span>`;
    }
}

/** The elf's verdict as a chip, or that it hasn't checked this head. */
function verdictChip(row) {
    if (!row.check) return html`<span class="chip">Not checked</span>`;
    const v = VERDICTS[row.check.verdict] ?? { cls: '', text: row.check.verdict };
    if (!row.current) return html`<span class="chip" title="Its verdict was for an older commit">Was ${v.text.toLowerCase()}</span>`;
    return html`<span class="chip ${v.cls}" title="Checked ${ago(row.check.at)}">${v.text}</span>`;
}

/** "lit 3.3.1 → 3.3.3", a major bump marked. */
function bumpChip(row) {
    const b = row.bump;
    if (!b) return '';
    return html`<span class="bump ${b.major ? 'major' : ''}" title="${b.groupId}:${b.artifactId}${b.major ? ', a major bump' : ''}">
        ${b.artifactId} <span class="from">${b.from}</span> → <b>${b.to}</b></span>`;
}

class MvnpmTab extends LitElement {

    static properties = {
        _board: { state: true },
        _state: { state: true },
        _running: { state: true },
        _loading: { state: true },
        _starting: { state: true },
        _doing: { state: true },
        _error: { state: true },
        _rowError: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .bar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; }
        .status { display: inline-flex; align-items: center; gap: 8px; font-size: 14px; color: var(--accent); }
        .failed { display: grid; gap: 8px; }
        pre {
            margin: 0; padding: 12px 14px; border-radius: var(--r-control); overflow-x: auto;
            background: var(--log-bg); color: var(--log-ink); font: 12.5px/1.5 var(--font-code); white-space: pre-wrap;
        }
        section { display: grid; gap: 12px; }
        .sec-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
        .sec-head h2 { font-size: 18px; font-weight: 600; letter-spacing: -0.01em; }
        .sec-head .n { color: var(--muted); font-size: 15px; }
        section.you > .panel { box-shadow: inset 3px 0 0 var(--accent); }
        ul { list-style: none; margin: 0; padding: 4px 0; }
        li {
            display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; gap: 4px 14px; align-items: start;
            padding: 12px 18px;
        }
        li + li { border-top: 1px solid var(--line); }
        li:hover { background: color-mix(in srgb, var(--surface-2) 55%, transparent); }
        li .icon { margin-top: 3px; }
        .main { min-width: 0; display: grid; gap: 5px; }
        .line1 { display: flex; align-items: baseline; gap: 8px; min-width: 0; flex-wrap: wrap; }
        .title { color: var(--ink); font-weight: 500; text-decoration: none; overflow-wrap: anywhere; }
        .title:hover { text-decoration: underline; }
        .bump { font-size: 13px; color: var(--muted); font-family: var(--font-code); }
        .bump .from { text-decoration: line-through; text-decoration-color: var(--line); }
        .bump b { color: var(--ink); font-weight: 600; }
        .bump.major b { color: var(--warn); }
        .meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; font-size: 13px; color: var(--muted); }
        .say { color: var(--ink); }
        .steps { display: inline-flex; align-items: center; gap: 6px; }
        .seg { display: inline-flex; gap: 3px; }
        .seg i { width: 14px; height: 4px; border-radius: 2px; background: var(--line); }
        .seg i.done { background: color-mix(in srgb, var(--accent) 45%, var(--line)); }
        .seg i.now { background: var(--accent); }
        .stage { font-weight: 500; }
        .acts { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; align-items: center; max-width: 26rem; }
        .row-error { margin: 8px 0 0; font-size: 13.5px; display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: center; }
        .empty { color: var(--muted); padding: 16px 18px; margin: 0; font-size: 14px; }
        .notice { padding: 18px 20px; display: grid; gap: 8px; max-width: 46rem; }
        .notice p { margin: 0; }
        .sk-row { display: grid; grid-template-columns: 16px 1fr 90px; gap: 14px; padding: 15px 18px; }
        .sk-row + .sk-row { border-top: 1px solid var(--line); }
        @media (max-width: 600px) {
            h1 { font-size: 26px; }
            li { grid-template-columns: 16px minmax(0, 1fr); padding: 12px 14px; }
            .acts { grid-column: 2; justify-content: flex-start; }
        }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._loading = true;
        this._doing = null;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        // A run started or ended (a refresh read GitHub, a check found its verdicts): read the files again.
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            this._state = await elf.state();
            this._board = await board();
            this._running = this._checking() ? await running() : null;
            this._error = null;
        } catch (e) {
            this._error = `Couldn't load the bumps: ${e.message}`;
        } finally {
            this._loading = false;
        }
    }

    /** The check command running now, if any: when it started. */
    _checking() {
        return CHECKS.map(c => this._state?.running?.[c]).find(Boolean) ?? null;
    }

    get _refreshing() {
        return this._starting === 'board' || !!this._state?.running?.board;
    }

    /** One of the elf's commands: Refresh (board), the round, or one bump's check. */
    async _run(command, params, rowKey) {
        this._starting = rowKey ?? command;
        this._error = null;
        this._rowError = null;
        try {
            this._state = await elf.run(command, params);
            await this._load();
        } catch (e) {
            if (rowKey) this._rowError = { key: rowKey, text: e.message };
            else this._error = e.message;
        } finally {
            this._starting = null;
        }
    }

    /** A row's step: a check, or one of the actions that write to GitHub once you confirm in the desk's bar. */
    async _step(row, next) {
        const pr = row.pr;
        if (next.id === 'check') return this._run('check', { repo: pr.repo.split('/')[1], pr: String(pr.number) }, key(pr));
        const values = { repo: pr.repo, pr: pr.number };
        if (next.id === 'merge') values.headSha = pr.headSha;
        this._doing = `${key(pr)} ${next.id}`;
        this._rowError = null;
        try {
            await elf.act(next.id, values);
            await this._load();
        } catch (e) {
            // Cancel in the desk's bar sent nothing: there's nothing to say.
            if (!e.cancelled) this._rowError = { key: key(pr), text: `${next.label}: ${e.message}` };
        } finally {
            this._doing = null;
        }
    }

    render() {
        const b = this._board;
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>mvnpm</h1>
                    <p class="sub">${b ? html`${count(b.rows.length, 'open bump')} in ${b.repos.join(', ')}. Updated ${ago(b.fetchedAt)}.`
                        : this._loading ? '' : 'Not read from GitHub yet.'}</p>
                </div>${settingsButton(null, 'mvnpm settings')}</div>
                ${this._bar()}
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${(b?.errors ?? []).map(e => html`<p class="error">GitHub couldn't be read for ${e}. Its bumps are from the read before.</p>`)}
                ${this._loading && !b ? this._skeleton() : b ? this._sections(b) : this._noBoard()}
            </div>`;
    }

    /** Refresh, the round, and what runs now (or the end of the last failed run's log). */
    _bar() {
        const checking = this._checking();
        const failed = ['board', ...CHECKS].map(c => !this._state?.running?.[c] && this._state?.failed?.[c]).find(Boolean);
        return html`
            <div class="bar">
                <button class="primary" ?disabled=${this._refreshing} @click=${() => this._run('board')}>
                    ${this._refreshing ? spinner : ''}${this._refreshing ? 'Refreshing' : 'Refresh'}</button>
                <button ?disabled=${!!checking || this._starting != null} title="Checks every open bump not checked at its head yet: it builds and tests them, comments and approves the safe ones"
                    @click=${() => this._run('round')}>${this._starting === 'round' ? spinner : ''}Check every open bump</button>
                ${checking ? html`<span class="status" role="status">${spinner}${checkingText(this._running)}, started ${ago(checking)}</span>` : ''}
            </div>
            ${failed ? html`<div class="failed" role="alert">
                <p class="error">The last run failed. The end of its log:</p><pre>${failed}</pre></div>` : ''}`;
    }

    _noBoard() {
        return html`<div class="panel notice">
            <p>${this._refreshing ? 'Reading the open mvnpm bumps from GitHub for the first time.'
                : "There's no board yet: the elf hasn't read GitHub."}</p></div>`;
    }

    _skeleton() {
        const rows = Array.from({ length: 3 }, () => html`<div class="sk-row"><div class="skeleton" style="height:16px"></div>
            <div style="display:grid;gap:8px"><div class="skeleton" style="height:12px;width:70%"></div>
            <div class="skeleton" style="height:12px;width:40%"></div></div><div class="skeleton" style="height:30px"></div></div>`);
        return html`<div class="panel" aria-busy="true" aria-label="Loading the bumps">${rows}</div>`;
    }

    _sections(b) {
        if (!b.rows.length) return html`<div class="panel"><p class="empty">No open mvnpm bumps from Dependabot.</p></div>`;
        const by = Object.fromEntries(SECTIONS.map(s => [s.id, []]));
        b.rows.forEach(r => (by[r.flow.waiting] ?? by.others).push(r));
        return SECTIONS.map(s => {
            const rows = by[s.id];
            if (!rows.length && s.id !== 'you') return '';
            return html`<section class=${s.id} aria-labelledby="h-${s.id}">
                <div class="sec-head"><h2 id="h-${s.id}">${s.title}</h2><span class="n">${rows.length}</span></div>
                <div class="panel">${rows.length ? html`<ul>${rows.map(r => this._row(r))}</ul>` : html`<p class="empty">${s.empty}</p>`}</div>
            </section>`;
        });
    }

    _row(row) {
        const pr = row.pr, f = row.flow;
        return html`<li>
            ${prIcon(pr)}
            <div class="main">
                <div class="line1">
                    <a class="title" href=${pr.url} target="_blank" rel="noopener">${pr.title}</a>
                </div>
                <div class="meta">
                    <span>${pr.repo} #${pr.number}</span>
                    ${bumpChip(row)}
                    ${verdictChip(row)}
                    ${ciChip(pr)}
                </div>
                <div class="meta">
                    ${this._stepper(f)}
                    <span class="say">${f.say}</span>
                    ${f.since ? html`<span>${ago(f.since)}</span>` : ''}
                </div>
                ${this._rowError?.key === key(pr) ? html`<p class="error row-error" role="alert">${this._rowError.text}
                    <button class="quiet sm" @click=${() => { this._rowError = null; }}>Dismiss</button></p>` : ''}
            </div>
            <div class="acts">${this._actions(row)}</div>
        </li>`;
    }

    /** "▬▭ Merge": how far along the flow is, and the stage it's at. */
    _stepper(f) {
        if (!f.steps?.length) return '';
        return html`<span class="steps" title=${f.steps.join(' → ')}>
            <span class="seg" aria-hidden="true">${f.steps.map((_, i) => html`<i class=${i < f.step ? 'done' : i === f.step ? 'now' : ''}></i>`)}</span>
            <span class="stage">${f.steps[f.step]}</span></span>`;
    }

    /** The main step, the others that fit, Check this one (whatever its verdict), and the PR on GitHub. */
    _actions(row) {
        const f = row.flow;
        const steps = [f.next, ...f.more].filter(Boolean);
        if (f.waiting !== 'agent' && !steps.some(n => n.id === 'check')) steps.push({ id: 'check', label: 'Check this one' });
        return html`${steps.map((n, i) => this._action(row, n, i === 0 && n === f.next))}
            ${steps.some(n => n.id === 'github') ? '' : html`<a class="btn sm quiet" href=${row.pr.url} target="_blank" rel="noopener">On GitHub</a>`}`;
    }

    _action(row, n, primary) {
        const cls = primary ? 'sm primary' : 'sm quiet';
        const pr = row.pr;
        switch (n.id) {
            case 'github':
                return html`<a class="btn ${cls}" href=${pr.url} target="_blank" rel="noopener">${n.label}</a>`;
            case 'checks':
                return html`<a class="btn ${cls}" href="${pr.url}/checks" target="_blank" rel="noopener">${n.label}</a>`;
            case 'comment':
                return row.check?.comment
                    ? html`<a class="btn ${cls}" href=${row.check.comment} target="_blank" rel="noopener">${n.label}</a>` : '';
            case 'check': {
                const busy = this._starting === key(pr);
                return html`<button class=${cls} ?disabled=${!!this._checking() || this._starting != null}
                    title="Checks it now, even if it was checked before: it builds and tests it, comments and approves it if safe"
                    @click=${() => this._step(row, n)}>${busy ? spinner : ''}${n.label}</button>`;
            }
            default: {
                const busy = this._doing === `${key(pr)} ${n.id}`;
                return html`<button class=${cls} ?disabled=${this._doing !== null} @click=${() => this._step(row, n)}>
                    ${busy ? spinner : ''}${n.label}</button>`;
            }
        }
    }
}

/** The dashboard card: how many bumps, how many are safe to merge, and attention when one is your move. */
class MvnpmCard extends LitElement {

    static properties = { _board: { state: true }, _state: { state: true }, _loaded: { state: true } };
    static styles = [shared, cardStyles];

    constructor() {
        super();
        new Ticking(this);
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
            this._board = await board();
            this._state = await elf.state();
        } catch {
            this._board = null;
        }
        this._loaded = true;
        elf.card({ attention: (this._board?.rows ?? []).some(r => r.flow.waiting === 'you') });
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        const b = this._board;
        const checking = CHECKS.map(c => this._state?.running?.[c]).find(Boolean);
        const failed = !checking && ['board', ...CHECKS].some(c => this._state?.failed?.[c]);
        let say;
        if (!b) {
            say = this._state?.running?.board ? 'Reading the open mvnpm bumps from GitHub for the first time.'
                : 'No bumps read yet. Refresh them from its tab.';
        } else if (!b.rows.length) {
            say = 'No open mvnpm bumps from Dependabot.';
        } else {
            const yours = b.rows.filter(r => r.flow.waiting === 'you').length;
            const safe = b.rows.filter(r => r.flow.next?.id === 'merge').length;
            const bumps = html`<a href="#">${count(b.rows.length, 'mvnpm bump')}</a>`;
            say = html`${bumps}${safe ? html`, ${safe} safe to merge` : ''}${yours
                ? html`. ${yours === b.rows.length ? (yours === 1 ? 'It needs' : 'All need') : `${yours} ${yours === 1 ? 'needs' : 'need'}`} your move.`
                : '. Nothing needs you.'}`;
        }
        return html`<p class="say">${say}</p>
            <div class="meta">${b ? html`<span>Updated ${ago(b.fetchedAt)}</span>` : ''}${checking
                ? html`<span class="running" role="status">${spinner}Checking, started ${ago(checking)}</span>`
                : failed ? html`<span class="error">The last run failed</span>` : ''}</div>`;
    }
}

customElements.define('mvnpm-tab', MvnpmTab);
customElements.define('mvnpm-card', MvnpmCard);
document.body.append(document.createElement(elf.view === 'card' ? 'mvnpm-card' : 'mvnpm-tab'));
