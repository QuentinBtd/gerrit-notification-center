// SPDX-License-Identifier: Apache-2.0
// Minimal public API subset, verified against Gerrit v3.14.4 app/api/{plugin,rest}.ts.
interface RestApi {
  getLoggedIn(): Promise<boolean>;
  get<T>(url: string): Promise<T>;
  fetch(method: 'POST', url: string, payload?: object): Promise<Response>;
}
interface PluginApi {
  url(): string;
  restApi(): RestApi;
  screen(name: string, component: string): unknown;
  registerCustomComponent(endpoint: string, component: string): unknown;
}
interface Notification {
  id: number; type: string; actor: number; project: string; change: number;
  url: string; created_at: number; read_at?: number; archived_at?: number; saved_at?: number;
}
interface Page {items: Notification[]; next?: number}
interface Count {unread: number; pollSeconds: number}
declare const Gerrit: {install(callback: (plugin: PluginApi) => void): void};
const path = '/accounts/self/notification-center~notifications';
// type -> [Material icon, reason label]
const types: Record<string, [string, string]> = {
  REVIEWER_ADDED: ['person_add', 'Review requested'], MENTION: ['alternate_email', 'Mentioned'],
  COMMENT: ['chat_bubble', 'Comment'], PATCHSET: ['upload', 'New patchset'],
  MERGED: ['merge', 'Merged'], ABANDONED: ['block', 'Abandoned'],
};
// UI tab -> REST state
const tabs: [string, string, string][] = [['all', 'Inbox', 'inbox'], ['saved', 'Saved', 'bookmark'], ['archived', 'Done', 'check']];
const icon = (name: string) => {const i = document.createElement('gr-icon'); i.setAttribute('icon', name); return i;};
function ago(ms: number): string {
  const s = (ms - Date.now()) / 1000, f = new Intl.RelativeTimeFormat(undefined, {numeric: 'auto'});
  for (const [unit, size] of [['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60]] as const)
    if (Math.abs(s) >= size) return f.format(Math.round(s / size), unit);
  return f.format(0, 'second');
}

class Inbox extends HTMLElement {
  protected bellOnly = false;
  private apiPlugin?: PluginApi;
  private rows: Notification[] = [];
  private next?: number;
  private unread = 0;
  private state = 'all';
  private type = '';
  private busy = false;
  private message = 'Loading…';
  private generation = 0;
  private timer?: ReturnType<typeof setTimeout>;
  private poll = 45000;
  private loggedIn = false;
  private stale = false; // a refresh was requested while another was running
  constructor() {super(); this.attachShadow({mode: 'open'});}
  set plugin(plugin: PluginApi) {
    this.apiPlugin = plugin;
    if (this.isConnected) void this.refresh();
  }
  get plugin(): PluginApi | undefined {return this.apiPlugin;}
  connectedCallback() {this.render(); if (this.plugin) void this.refresh();}
  disconnectedCallback() {this.generation++; this.busy = false; this.stale = false; clearTimeout(this.timer);}
  private rootUrl(relative: string): string {
    const gerritRoot = new URL('../../', this.plugin!.url());
    return new URL(relative.replace(/^\//, ''), gerritRoot).toString();
  }
  private async refresh(append = false): Promise<void> {
    if (!this.plugin || !this.isConnected) return;
    if (this.busy) {this.stale = true; return;}
    this.stale = false;
    this.busy = true; clearTimeout(this.timer);
    const generation = this.generation;
    this.render();
    try {
      const api = this.plugin.restApi();
      const loggedIn = await api.getLoggedIn();
      if (generation !== this.generation || !this.isConnected) return;
      this.loggedIn = loggedIn;
      if (!loggedIn) {this.rows = []; this.unread = 0; this.message = 'Sign in to view notifications.'; return;}
      const count = await api.get<Count>(path + '?count=true');
      let page: Page | undefined;
      if (!this.bellOnly) {
        const q = new URLSearchParams({state: this.state, limit: '50'});
        if (this.type) q.set('type', this.type);
        if (append && this.next) q.set('before', String(this.next));
        page = await api.get<Page>(path + '?' + q);
      }
      if (generation !== this.generation || !this.isConnected) return;
      this.unread = count.unread;
      this.poll = Math.min(3600, Math.max(30, count.pollSeconds || 45)) * 1000;
      if (page) {this.rows = append ? [...this.rows, ...page.items] : page.items; this.next = page.next;}
      this.message = '';
    } catch {
      if (generation === this.generation) {this.rows = []; this.next = undefined; this.unread = 0; this.message = 'Unable to load notifications. Retry or check your session.';}
    } finally {
      if (generation === this.generation && this.isConnected) {
        this.busy = false; this.render();
        // Responses started before the request may predate a save, so re-read right away.
        if (this.stale) void this.refresh();
        else this.timer = setTimeout(() => void this.refresh(), this.poll);
      }
    }
  }
  private async action(action: string, id?: number): Promise<void> {
    if (this.busy || !this.plugin || !this.isConnected) return;
    this.busy = true; clearTimeout(this.timer); this.render();
    const generation = this.generation;
    try {
      const response = await this.plugin.restApi().fetch('POST', path, {action, ...(id === undefined ? {} : {id})});
      if (!response.ok) throw new Error('Unable to save notification');
      if (generation !== this.generation || !this.isConnected) return;
      this.busy = false; await this.refresh();
    } catch {
      if (generation === this.generation && this.isConnected) {
        this.busy = false; this.rows = []; this.message = 'Unable to save. Retry or refresh notifications.'; this.render();
        this.timer = setTimeout(() => void this.refresh(), this.poll);
      }
    }
  }
  // Opening a change must mark it read even while a refresh or another save is in flight.
  private async markOpened(n: Notification): Promise<void> {
    try {
      const response = await this.plugin!.restApi().fetch('POST', path, {action: 'read', id: n.id});
      if (!response.ok) throw new Error('Unable to save notification');
      n.read_at = Date.now();
      if (this.isConnected) {this.render(); void this.refresh();}
    } catch {
      if (this.isConnected) {this.message = 'Unable to save. Retry or refresh notifications.'; this.render();}
    }
  }
  private button(name: string, label: string, action: () => void, text = false): HTMLButtonElement {
    const b = document.createElement('button'); b.append(icon(name)); b.title = label;
    b.setAttribute('aria-label', label); if (text) b.append(label);
    b.disabled = this.busy; b.onclick = action; return b;
  }
  private render() {
    const root = this.shadowRoot!; root.replaceChildren();
    const style = document.createElement('style');
    style.textContent = `
:host{display:block;color:var(--primary-text-color);font-family:var(--font-family,sans-serif);font-size:var(--font-size-normal,14px)}
a{color:inherit;text-decoration:none}
gr-icon{font-size:20px;vertical-align:middle}
button{display:inline-flex;align-items:center;gap:6px;font:inherit;color:var(--deemphasized-text-color);background:none;border:0;border-radius:6px;padding:6px;cursor:pointer}
button:hover{background:var(--hover-background-color,rgba(127,127,127,.12));color:var(--primary-text-color)}
button:disabled{cursor:wait;opacity:.5}
select{font:inherit;color:inherit;background:var(--background-color-primary);border:1px solid var(--border-color);border-radius:6px;padding:5px 8px}
.layout{display:grid;grid-template-columns:200px minmax(0,1fr);gap:24px;max-width:72rem;margin:0 auto;padding:24px 16px}
.side{display:flex;flex-direction:column;gap:2px}
.side button{justify-content:flex-start;width:100%;padding:8px 10px;color:var(--primary-text-color)}
button[aria-pressed=true]{background:var(--selection-background-color,rgba(25,103,210,.12));color:var(--primary-text-color)}
.side button[aria-current=page]{background:var(--selection-background-color,rgba(25,103,210,.12));font-weight:600}
.side .n{margin-left:auto;font-size:12px;color:var(--deemphasized-text-color)}
.panel{border:1px solid var(--border-color);border-radius:8px;background:var(--background-color-primary);overflow:hidden}
.bar{display:flex;align-items:center;gap:8px;padding:8px 12px;border-bottom:1px solid var(--border-color);background:var(--background-color-secondary)}
.bar .grow{flex:1}
.row{display:flex;align-items:center;gap:12px;padding:10px 12px;border-bottom:1px solid var(--border-color)}
.row:last-child{border-bottom:0}
.row:hover{background:var(--hover-background-color,rgba(127,127,127,.06))}
.dot{width:8px;height:8px;border-radius:50%;flex:none}
.unread .dot{background:var(--link-color,#1967d2)}
.kind{color:var(--deemphasized-text-color)}
.main{flex:1;min-width:0}
.title{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.unread .title{font-weight:600}
.meta{font-size:12px;color:var(--deemphasized-text-color)}
.when{font-size:12px;color:var(--deemphasized-text-color);white-space:nowrap}
.actions{display:flex;opacity:.35}
.row:hover .actions,.row:focus-within .actions{opacity:1}
.empty{padding:48px 16px;text-align:center;color:var(--deemphasized-text-color)}
.more{display:flex;justify-content:center;padding:8px}
.bell{position:relative;display:inline-flex;align-items:center;justify-content:center;width:40px;height:40px;border-radius:50%;color:var(--header-text-color,inherit)}
.bell:hover{background:color-mix(in srgb,currentColor 8%,transparent)}
.bell:active{background:color-mix(in srgb,currentColor 12%,transparent)}
.bell:focus-visible{outline:2px solid var(--link-color,#1967d2);outline-offset:-2px}
.bell gr-icon{font-size:24px}
.badge{position:absolute;top:4px;right:2px;min-width:16px;padding:0 3px;box-sizing:border-box;border-radius:8px;background:var(--error-foreground,#d93025);color:#fff;font-size:11px;line-height:16px;text-align:center}
@media (max-width:700px){.layout{grid-template-columns:1fr}.side{flex-direction:row}.actions{opacity:1}}`;
    root.append(style);
    if (this.bellOnly) {
      if (!this.loggedIn) return;
      const a = document.createElement('a'); a.className = 'bell';
      a.href = this.rootUrl('x/notification-center/notifications');
      a.title = 'Notifications';
      a.setAttribute('aria-label', `Notifications, ${this.unread} unread`);
      const icon = document.createElement('gr-icon'); icon.setAttribute('icon', 'notifications'); a.append(icon);
      if (this.unread) {const badge = document.createElement('span'); badge.className = 'badge'; badge.textContent = this.unread > 99 ? '99+' : String(this.unread); a.append(badge);}
      root.append(a); return;
    }
    const layout = document.createElement('div'); layout.className = 'layout';
    const side = document.createElement('nav'); side.className = 'side'; side.setAttribute('aria-label', 'Notification folders');
    for (const [state, label, name] of tabs) {
      const b = this.button(name, label, () => {this.state = state; this.next = undefined; void this.refresh();}, true);
      if (state === this.state || (state === 'all' && this.state === 'unread')) b.setAttribute('aria-current', 'page');
      if (state === 'all' && this.unread) {const n = document.createElement('span'); n.className = 'n'; n.textContent = String(this.unread); b.append(n);}
      side.append(b);
    }
    const panel = document.createElement('main'); panel.className = 'panel';
    layout.append(side, panel);
    if (this.loggedIn) {
      const bar = document.createElement('div'); bar.className = 'bar';
      const type = document.createElement('select'); type.setAttribute('aria-label', 'Event type');
      for (const value of ['', ...Object.keys(types)]) {const o = document.createElement('option'); o.value = value; o.textContent = value ? types[value][1] : 'All types'; type.append(o);}
      type.value = this.type; type.disabled = this.busy;
      type.onchange = () => {this.type = type.value; this.next = undefined; void this.refresh();};
      const grow = document.createElement('span'); grow.className = 'grow';
      bar.append(type, grow);
      if (this.state === 'all' || this.state === 'unread') {
        const unread = this.button('filter_list', 'Unread', () => {this.state = this.state === 'unread' ? 'all' : 'unread'; this.next = undefined; void this.refresh();}, true);
        unread.setAttribute('aria-pressed', String(this.state === 'unread'));
        bar.insertBefore(unread, grow);
        bar.append(this.button('done_all', 'Mark all as read', () => void this.action('read-all'), true));
      }
      bar.append(this.button('refresh', 'Refresh', () => void this.refresh()));
      panel.append(bar);
      for (const n of this.rows) {
        const row = document.createElement('div'); row.className = n.read_at ? 'row' : 'row unread';
        const dot = document.createElement('span'); dot.className = 'dot';
        const [kindIcon, reason] = types[n.type] ?? ['notifications', n.type];
        const kind = icon(kindIcon); kind.className = 'kind'; kind.title = reason;
        const main = document.createElement('div'); main.className = 'main';
        const a = document.createElement('a'); a.className = 'title'; a.dataset.change = String(n.change);
        if (/^\/c\//.test(n.url)) a.href = this.rootUrl(n.url);
        a.textContent = `${n.project} #${n.change}`;
        if (!n.read_at) a.onclick = () => void this.markOpened(n);
        const meta = document.createElement('div'); meta.className = 'meta'; meta.textContent = `${reason} · Account ${n.actor}`;
        main.append(a, meta);
        const when = document.createElement('time'); when.className = 'when';
        when.dateTime = new Date(n.created_at).toISOString(); when.title = new Date(n.created_at).toLocaleString(); when.textContent = ago(n.created_at);
        const actions = document.createElement('div'); actions.className = 'actions';
        actions.append(
          n.read_at ? this.button('mark_email_unread', 'Mark as unread', () => void this.action('unread', n.id))
            : this.button('mark_email_read', 'Mark as read', () => void this.action('read', n.id)),
          n.saved_at ? this.button('bookmark_remove', 'Unsave', () => void this.action('unsave', n.id))
            : this.button('bookmark_add', 'Save', () => void this.action('save', n.id)),
          n.archived_at ? this.button('move_to_inbox', 'Move to inbox', () => void this.action('unarchive', n.id))
            : this.button('check', 'Mark as done', () => void this.action('archive', n.id)));
        row.append(dot, kind, main, when, actions); panel.append(row);
      }
      if (!this.rows.length) {const e = document.createElement('p'); e.className = 'empty'; e.setAttribute('role', 'status'); e.textContent = this.busy ? 'Loading…' : this.message || 'All caught up!'; panel.append(e);}
      else if (this.message) {const e = document.createElement('p'); e.setAttribute('role', 'status'); e.className = 'empty'; e.textContent = this.message; panel.append(e);}
      if (this.next) {const more = document.createElement('div'); more.className = 'more'; more.append(this.button('expand_more', 'Load more', () => void this.refresh(true), true)); panel.append(more);}
    } else {
      const e = document.createElement('p'); e.className = 'empty'; e.setAttribute('role', 'status'); e.textContent = this.message; panel.append(e);
      if (!this.busy) panel.append(this.button('refresh', 'Retry', () => void this.refresh(), true));
    }
    root.append(layout);
  }
}
class Bell extends Inbox {protected override bellOnly = true;}
customElements.define('notification-center-screen', Inbox);
customElements.define('notification-center-bell', Bell);
Gerrit.install(plugin => {
  plugin.screen('notifications', 'notification-center-screen');
  plugin.registerCustomComponent('header-top-right', 'notification-center-bell');
});
