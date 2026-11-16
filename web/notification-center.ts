// SPDX-License-Identifier: Apache-2.0
// Minimal public API subset, verified against Gerrit v3.14.4 app/api/{plugin,rest}.ts.
interface RestApi {
  getLoggedIn(): Promise<boolean>;
  get<T>(url: string): Promise<T>;
  post<T>(url: string, payload?: object): Promise<T>;
}
interface PluginApi {
  url(): string;
  restApi(): RestApi;
  screen(name: string, component: string): unknown;
  registerCustomComponent(endpoint: string, component: string): unknown;
}
interface Notification {
  id: number; type: string; actor: number; project: string; change: number;
  url: string; created_at: number; read_at?: number; archived_at?: number;
}
interface Page {items: Notification[]; next?: number}
interface Count {unread: number; pollSeconds: number}
declare const Gerrit: {install(callback: (plugin: PluginApi) => void): void};
const path = '/accounts/self/notification-center~notifications';
const types = ['REVIEWER_ADDED', 'MENTION', 'COMMENT', 'PATCHSET', 'MERGED', 'ABANDONED'];

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
  constructor() {super(); this.attachShadow({mode: 'open'});}
  set plugin(plugin: PluginApi) {
    this.apiPlugin = plugin;
    if (this.isConnected) void this.refresh();
  }
  get plugin(): PluginApi | undefined {return this.apiPlugin;}
  connectedCallback() {this.render(); if (this.plugin) void this.refresh();}
  disconnectedCallback() {this.generation++; this.busy = false; clearTimeout(this.timer);}
  private rootUrl(relative: string): string {
    return new URL(relative.replace(/^\//, ''), this.plugin!.url()).toString();
  }
  private async refresh(append = false): Promise<void> {
    if (this.busy || !this.plugin || !this.isConnected) return;
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
      this.message = this.rows.length ? '' : 'No notifications.';
    } catch {
      if (generation === this.generation) {this.rows = []; this.next = undefined; this.unread = 0; this.message = 'Unable to load notifications. Retry or check your session.';}
    } finally {
      if (generation === this.generation && this.isConnected) {
        this.busy = false; this.render();
        this.timer = setTimeout(() => void this.refresh(), this.poll);
      }
    }
  }
  private async action(action: string, id?: number): Promise<void> {
    if (this.busy || !this.plugin || !this.isConnected) return;
    this.busy = true; clearTimeout(this.timer); this.render();
    const generation = this.generation;
    try {
      await this.plugin.restApi().post(path, {action, ...(id === undefined ? {} : {id})});
      if (generation !== this.generation || !this.isConnected) return;
      this.busy = false; await this.refresh();
    } catch {
      if (generation === this.generation && this.isConnected) {
        this.busy = false; this.rows = []; this.message = 'Unable to save. Retry or refresh notifications.'; this.render();
        this.timer = setTimeout(() => void this.refresh(), this.poll);
      }
    }
  }
  private button(text: string, action: () => void): HTMLButtonElement {
    const b = document.createElement('button'); b.textContent = text;
    b.disabled = this.busy; b.onclick = action; return b;
  }
  private render() {
    const root = this.shadowRoot!; root.replaceChildren();
    const style = document.createElement('style');
    style.textContent = `:host{display:block;color:var(--primary-text-color);font-family:var(--font-family, sans-serif);font-size:var(--font-size-normal,14px)}section{background:var(--view-background-color);padding:var(--spacing-l,16px);max-width:70rem;margin:auto}h1{font-size:var(--font-size-h2,24px)}nav{display:flex;flex-wrap:wrap;gap:var(--spacing-m,8px);align-items:center}article{padding:var(--spacing-m,8px);border-bottom:1px solid var(--border-color,#ddd)}article.unread{border-inline-start:3px solid var(--link-color,#1967d2)}a{color:var(--link-color,#1967d2)}button,select{font:inherit;color:inherit;background:var(--view-background-color);border:1px solid var(--border-color,#aaa);border-radius:4px;padding:6px;margin:4px}button{cursor:pointer}button:disabled{cursor:wait;opacity:.6}.meta{color:var(--deemphasized-text-color);margin:6px 0}.bell{padding:4px;display:inline-block}`;
    root.append(style);
    if (this.bellOnly) {
      if (!this.loggedIn) return;
      const a = document.createElement('a'); a.className = 'bell';
      a.href = this.rootUrl('x/notification-center/notifications');
      a.textContent = `🔔 Notifications (${this.unread})`;
      a.setAttribute('aria-label', `${this.unread} unread notifications`); root.append(a); return;
    }
    const section = document.createElement('section');
    const title = document.createElement('h1'); title.textContent = `Notifications (${this.unread} unread)`; section.append(title);
    const notice = document.createElement('p'); notice.setAttribute('role', 'status'); notice.textContent = this.message; section.append(notice);
    if (this.loggedIn) {
      const nav = document.createElement('nav'); nav.setAttribute('aria-label', 'Notification filters');
      const state = document.createElement('select'); state.setAttribute('aria-label', 'State');
      for (const [value, label] of [['all', 'All'], ['unread', 'Unread'], ['archived', 'Archived']]) {
        const option = document.createElement('option'); option.value = value; option.textContent = label; state.append(option);
      }
      state.value = this.state; state.disabled = this.busy;
      state.onchange = () => {this.state = state.value; this.next = undefined; void this.refresh();};
      const type = document.createElement('select'); type.setAttribute('aria-label', 'Event type');
      for (const value of ['', ...types]) {const o = document.createElement('option'); o.value = value; o.textContent = value || 'All types'; type.append(o);}
      type.value = this.type; type.disabled = this.busy;
      type.onchange = () => {this.type = type.value; this.next = undefined; void this.refresh();};
      nav.append(state, type, this.button('Mark all read', () => void this.action('read-all')), this.button('Refresh', () => void this.refresh())); section.append(nav);
      for (const n of this.rows) {
        const article = document.createElement('article'); if (!n.read_at) article.className = 'unread';
        const a = document.createElement('a'); a.dataset.change = String(n.change);
        if (/^\/c\//.test(n.url)) a.href = this.rootUrl(n.url);
        a.textContent = `${n.type}: ${n.project} #${n.change}`;
        const meta = document.createElement('p'); meta.className = 'meta'; meta.textContent = `Account ${n.actor} · ${new Date(n.created_at).toLocaleString()}`;
        article.append(a, meta, this.button(n.read_at ? 'Mark unread' : 'Mark read', () => void this.action(n.read_at ? 'unread' : 'read', n.id)));
        if (!n.archived_at) article.append(this.button('Archive', () => void this.action('archive', n.id)));
        section.append(article);
      }
      if (this.next) section.append(this.button('Load more', () => void this.refresh(true)));
    } else if (!this.busy) section.append(this.button('Retry', () => void this.refresh()));
    root.append(section);
  }
}
class Bell extends Inbox {protected override bellOnly = true;}
customElements.define('notification-center-screen', Inbox);
customElements.define('notification-center-bell', Bell);
Gerrit.install(plugin => {
  plugin.screen('notifications', 'notification-center-screen');
  plugin.registerCustomComponent('footer-left', 'notification-center-bell');
});
