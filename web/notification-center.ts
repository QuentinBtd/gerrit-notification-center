// SPDX-License-Identifier: Apache-2.0
// Minimal public API subset, verified against Gerrit v3.14.4 app/api/{plugin,rest}.ts.
interface RestApi {
  getLoggedIn(): Promise<boolean>;
  get<T>(url: string): Promise<T>;
  fetch(method: 'POST' | 'PUT', url: string, payload?: object): Promise<Response>;
}
interface PluginApi {
  url(): string;
  restApi(): RestApi;
  screen(name: string, component: string): unknown;
  registerCustomComponent(endpoint: string, component: string): unknown;
}
interface Item {
  id: number; type: string; actor: number; project: string; change: number;
  url: string; created_at: number; read_at?: number; archived_at?: number; saved_at?: number;
}
interface Page {items: Item[]; next?: number}
interface Count {unread: number; latest: number; pollSeconds: number; browser: boolean}
interface Subscription {query: string; types: string[]}
interface Prefs {email: boolean; browser: boolean; subscriptions: Subscription[]}
declare const Gerrit: {install(callback: (plugin: PluginApi) => void): void};
const path = '/accounts/self/notification-center~notifications';
const prefsPath = '/accounts/self/notification-center~preferences';
const lastSeenKey = 'notification-center-last-browser-id';
const pageSize = 25;
// Same-page signal from the Notifications screen to the header bell after a successful change.
const changedEvent = 'notification-center-changed';
const announceChange = () => document.dispatchEvent(new Event(changedEvent));
// type -> [Material icon, reason label]
const types: Record<string, [string, string]> = {
  REVIEWER_ADDED: ['person_add', 'Review requested'], MENTION: ['alternate_email', 'Mentioned'],
  COMMENT: ['chat_bubble', 'Comment'], NEW_CHANGE: ['add_circle', 'New change'], PATCHSET: ['upload', 'New patchset'],
  MERGED: ['merge', 'Merged'], ABANDONED: ['block', 'Abandoned'],
};
const subscribable = ['NEW_CHANGE', 'PATCHSET', 'COMMENT', 'MERGED', 'ABANDONED'];
// UI tab -> REST state
const tabs: [string, string, string][] = [['all', 'Inbox', 'inbox'], ['saved', 'Saved', 'bookmark'], ['archived', 'Done', 'check'], ['settings', 'Settings', 'settings']];
// Actor account ID -> display name, resolved once per page load via Gerrit's account API.
const names = new Map<number, string>(), pending = new Set<number>();
const actorName = (id: number) => names.get(id) ?? `Account ${id}`;
// Never awaited: rows show "Account N" at once and `resolved(id)` patches them as names arrive.
function loadNames(api: RestApi, ids: number[], resolved: (id: number) => void): void {
  for (const id of new Set(ids)) {
    if (names.has(id) || pending.has(id)) continue;
    pending.add(id);
    api.get<{display_name?: string; name?: string; username?: string}>(`/accounts/${id}`)
      .then(a => {names.set(id, a.display_name || a.name || a.username || `Account ${id}`);},
        () => {names.set(id, `Account ${id}`);}) // not visible or deleted: keep the ID, don't refetch
      .finally(() => {pending.delete(id); resolved(id);});
  }
}
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
  private rows: Item[] = [];
  private next?: number; // cursor of the page after this one
  private cursors: (number | undefined)[] = [undefined]; // `before` of each visited page
  private pageIndex = 0;
  private selected = new Set<number>();
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
  private prefs?: Prefs;
  constructor() {super(); this.attachShadow({mode: 'open'});}
  set plugin(plugin: PluginApi) {
    this.apiPlugin = plugin;
    if (this.isConnected) void this.refresh();
  }
  get plugin(): PluginApi | undefined {return this.apiPlugin;}
  private readonly sync = () => {if (document.visibilityState !== 'hidden') void this.refresh();};
  connectedCallback() {
    this.render(); if (this.plugin) void this.refresh();
    // Background tabs throttle the poll timer: catch up as soon as the tab is visible again.
    document.addEventListener('visibilitychange', this.sync);
    if (this.bellOnly) document.addEventListener(changedEvent, this.sync);
  }
  disconnectedCallback() {
    this.generation++; this.busy = false; this.stale = false; clearTimeout(this.timer);
    document.removeEventListener('visibilitychange', this.sync);
    document.removeEventListener(changedEvent, this.sync);
  }
  private rootUrl(relative: string): string {
    const gerritRoot = new URL('../../', this.plugin!.url());
    return new URL(relative.replace(/^\//, ''), gerritRoot).toString();
  }
  private resetPaging() {this.next = undefined; this.cursors = [undefined]; this.pageIndex = 0; this.selected.clear();}
  private async refresh(): Promise<void> {
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
      if (this.bellOnly) await this.notifyBrowser(api, count);
      else if (this.state === 'settings') this.prefs ??= await api.get<Prefs>(prefsPath);
      else {
        const q = new URLSearchParams({state: this.state, limit: String(pageSize)});
        if (this.type) q.set('type', this.type);
        const before = this.cursors[this.pageIndex];
        if (before) q.set('before', String(before));
        page = await api.get<Page>(path + '?' + q);
        // Patch only the actor text: a full render would drop focus (e.g. while editing Settings).
        loadNames(api, page.items.map(n => n.actor), id => {
          for (const m of this.shadowRoot!.querySelectorAll<HTMLElement>(`[data-actor="${id}"]`))
            m.textContent = `${m.dataset.reason} · ${actorName(id)}`;
        });
      }
      if (generation !== this.generation || !this.isConnected) return;
      this.unread = count.unread;
      this.poll = Math.min(3600, Math.max(30, count.pollSeconds || 45)) * 1000;
      // A page emptied by Done/archive steps back instead of showing an empty page.
      if (page && !page.items.length && this.pageIndex > 0) {this.pageIndex--; this.stale = true;}
      else if (page) {
        this.rows = page.items; this.next = page.next;
        const onPage = new Set(page.items.map(n => n.id));
        for (const id of this.selected) if (!onPage.has(id)) this.selected.delete(id);
      }
      this.message = '';
    } catch {
      if (generation === this.generation) {this.rows = []; this.next = undefined; this.unread = 0; this.message = 'Unable to load notifications. Retry or check your session.';}
    } finally {
      if (generation === this.generation && this.isConnected) {
        this.busy = false; this.render();
        // Responses started before the request may predate a save, so re-read right away.
        if (this.stale) void this.refresh();
        // No polling on Settings: a re-render would drop unsaved edits and focus.
        else if (this.bellOnly || this.state !== 'settings') this.timer = setTimeout(() => void this.refresh(), this.poll);
      }
    }
  }
  private async action(action: string, target?: number | number[]): Promise<void> {
    if (this.busy || !this.plugin || !this.isConnected) return;
    this.busy = true; clearTimeout(this.timer); this.render();
    const generation = this.generation;
    try {
      const payload = Array.isArray(target) ? {action, ids: target} : target === undefined ? {action} : {action, id: target};
      const response = await this.plugin.restApi().fetch('POST', path, payload);
      if (!response.ok) throw new Error('Unable to save notification');
      if (generation !== this.generation || !this.isConnected) return;
      announceChange();
      if (Array.isArray(target)) this.selected.clear();
      this.busy = false; await this.refresh();
    } catch {
      if (generation === this.generation && this.isConnected) {
        this.busy = false; this.rows = []; this.message = 'Unable to save. Retry or refresh notifications.'; this.render();
        this.timer = setTimeout(() => void this.refresh(), this.poll);
      }
    }
  }
  // Opening a change must mark it read even while a refresh or another save is in flight.
  private async markOpened(n: Item): Promise<void> {
    try {
      const response = await this.plugin!.restApi().fetch('POST', path, {action: 'read', id: n.id});
      if (!response.ok) throw new Error('Unable to save notification');
      n.read_at = Date.now();
      announceChange(); // also when opening the Change already navigated away from this screen
      if (this.isConnected) {this.render(); void this.refresh();}
    } catch {
      if (this.isConnected) {this.message = 'Unable to save. Retry or refresh notifications.'; this.render();}
    }
  }
  // Shows OS notifications for unread items newer than the last one shown in this browser.
  private async notifyBrowser(api: RestApi, count: Count): Promise<void> {
    if (!count.browser || typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
    const save = (id: number) => {try {localStorage.setItem(lastSeenKey, String(id));} catch { /* storage blocked */ }};
    let stored: string | null = null;
    try {stored = localStorage.getItem(lastSeenKey);} catch { /* storage blocked */ }
    // First poll in this browser: record a starting point (0 for an empty inbox), no burst of old items.
    if (stored === null) {save(count.latest); return;}
    const last = Number(stored) || 0;
    if (count.latest <= last) return;
    try {
      const page = await api.get<Page>(path + '?state=unread&limit=10');
      loadNames(api, page.items.map(n => n.actor), () => {}); // alerts use whatever is known now
      let newest = count.latest;
      for (const n of page.items.filter(n => n.id > last).reverse()) {
        newest = Math.max(newest, n.id); // may be newer than the count if it arrived in between
        const [, reason] = types[n.type] ?? ['', n.type];
        // The tag lets the browser collapse duplicates shown by several open Gerrit tabs.
        const shown = new Notification(`${reason}: ${n.project} #${n.change}`, {body: actorName(n.actor), tag: `notification-center-${n.id}`});
        if (/^\/c\//.test(n.url)) shown.onclick = () => {window.focus(); location.href = this.rootUrl(n.url);};
      }
      save(newest); // only once shown: a failed fetch is retried on the next poll
    } catch { /* keep the bell working; retry next poll */ }
  }
  private async savePrefs(): Promise<void> {
    if (!this.prefs || this.busy) return;
    this.busy = true; this.message = ''; this.render();
    try {
      const response = await this.plugin!.restApi().fetch('PUT', prefsPath, this.prefs);
      // 400 carries our own validation text (e.g. an invalid query); other failures stay generic.
      if (response.status === 400) this.message = (await response.text()).trim();
      else if (!response.ok) throw new Error('save failed');
      else {this.prefs = await this.plugin!.restApi().get<Prefs>(prefsPath); this.message = 'Saved.';}
    } catch {
      this.message = 'Unable to save settings. Retry or check your session.';
    } finally {this.busy = false; if (this.isConnected) this.render();}
  }
  private renderSettings(panel: HTMLElement) {
    const prefs = this.prefs;
    if (!prefs) {const e = document.createElement('p'); e.className = 'empty'; e.setAttribute('role', 'status'); e.textContent = this.busy ? 'Loading…' : this.message; panel.append(e); return;}
    const form = document.createElement('div'); form.className = 'settings';
    const check = (label: string, checked: boolean, change: (on: boolean, box: HTMLInputElement) => void) => {
      const l = document.createElement('label'); const box = document.createElement('input'); box.type = 'checkbox';
      box.checked = checked; box.disabled = this.busy; box.onchange = () => change(box.checked, box);
      l.append(box, label); return l;
    };
    const heading = (text: string) => {const h = document.createElement('h2'); h.textContent = text; return h;};
    const hint = (text: string) => {const p = document.createElement('p'); p.className = 'meta'; p.textContent = text; return p;};
    form.append(heading('Delivery'), hint('Notifications always appear in this inbox. Also send them by:'),
      check('Email (to your preferred email address)', prefs.email, on => {prefs.email = on;}),
      check('Browser notification (while a Gerrit tab is open)', prefs.browser, async (on, box) => {
        prefs.browser = on;
        if (!on || typeof Notification === 'undefined' || Notification.permission === 'granted') return;
        if (await Notification.requestPermission() !== 'granted') {
          prefs.browser = box.checked = false;
          this.message = 'Browser notifications are blocked for this site in your browser settings.'; this.render();
        }
      }));
    const native = document.createElement('a'); native.href = this.rootUrl('settings/#Notifications'); native.textContent = 'Gerrit watched projects';
    const intro = hint('Get notified about any change matching a change search query, e.g. project:^team/.* branch:main -is:wip. Your ');
    intro.append(native, ' keep notifying here too.');
    form.append(heading('Subscriptions'), intro);
    prefs.subscriptions.forEach((sub, i) => {
      const row = document.createElement('div'); row.className = 'sub';
      const q = document.createElement('input'); q.type = 'text'; q.value = sub.query; q.placeholder = 'project:^team/.* branch:main';
      q.setAttribute('aria-label', 'Subscription query'); q.disabled = this.busy; q.oninput = () => {sub.query = q.value;};
      const kinds = document.createElement('div'); kinds.className = 'kinds';
      for (const t of subscribable) kinds.append(check(types[t][1], sub.types.includes(t), on => {sub.types = on ? [...sub.types, t] : sub.types.filter(x => x !== t);}));
      row.append(q, kinds, this.button('delete', 'Remove subscription', () => {prefs.subscriptions.splice(i, 1); this.render();}));
      form.append(row);
    });
    const actions = document.createElement('div'); actions.className = 'bar';
    const grow = document.createElement('span'); grow.className = 'grow';
    actions.append(this.button('add', 'Add subscription', () => {prefs.subscriptions.push({query: '', types: [...subscribable]}); this.render();}, true),
      grow, this.button('save', 'Save settings', () => void this.savePrefs(), true));
    form.append(actions);
    if (this.message) {const m = document.createElement('p'); m.setAttribute('role', 'status'); m.className = 'meta'; m.textContent = this.message; form.append(m);}
    panel.append(form);
  }
  private button(name: string, label: string, action: () => void, text = false): HTMLButtonElement {
    const b = document.createElement('button'); b.append(icon(name)); b.title = label;
    b.setAttribute('aria-label', label); if (text) b.append(label);
    b.disabled = this.busy; b.onclick = action; return b;
  }
  private render() {
    const root = this.shadowRoot!;
    // Rendering rebuilds the DOM: keep keyboard focus on the same selection checkbox.
    const focusKey = (root.activeElement as HTMLElement | null)?.dataset?.focus;
    root.replaceChildren();
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
.bar{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:8px 12px;border-bottom:1px solid var(--border-color);background:var(--background-color-secondary)}
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
.row.selected{background:var(--selection-background-color,rgba(25,103,210,.08))}
input[type=checkbox]{margin:0;flex:none}
.range{font-size:12px;color:var(--deemphasized-text-color);white-space:nowrap}
.settings{padding:4px 16px 16px}
.settings h2{font-size:var(--font-size-h3,16px);margin:16px 0 4px}
.settings label{display:flex;align-items:center;gap:6px;padding:3px 0}
.settings .bar{border:0;background:none;padding:12px 0 0}
.sub{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:8px 0;border-bottom:1px solid var(--border-color)}
.sub input[type=text]{flex:1 1 20rem;font:inherit;color:inherit;background:var(--background-color-primary);border:1px solid var(--border-color);border-radius:6px;padding:5px 8px}
.kinds{display:flex;flex-wrap:wrap;gap:0 12px}
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
      const b = this.button(name, label, () => {this.state = state; this.resetPaging(); this.prefs = undefined; this.message = ''; void this.refresh();}, true);
      if (state === this.state || (state === 'all' && this.state === 'unread')) b.setAttribute('aria-current', 'page');
      if (state === 'all' && this.unread) {const n = document.createElement('span'); n.className = 'n'; n.textContent = String(this.unread); b.append(n);}
      side.append(b);
    }
    const panel = document.createElement('main'); panel.className = 'panel';
    layout.append(side, panel);
    if (this.loggedIn && this.state === 'settings') this.renderSettings(panel);
    else if (this.loggedIn) {
      const bar = document.createElement('div'); bar.className = 'bar';
      const type = document.createElement('select'); type.setAttribute('aria-label', 'Event type');
      for (const value of ['', ...Object.keys(types)]) {const o = document.createElement('option'); o.value = value; o.textContent = value ? types[value][1] : 'All types'; type.append(o);}
      type.value = this.type; type.disabled = this.busy;
      type.onchange = () => {this.type = type.value; this.resetPaging(); void this.refresh();};
      const grow = document.createElement('span'); grow.className = 'grow';
      const all = document.createElement('input'); all.type = 'checkbox'; all.setAttribute('aria-label', 'Select all on this page'); all.dataset.focus = 'select-all';
      const picked = this.rows.filter(n => this.selected.has(n.id)).length;
      all.checked = picked > 0 && picked === this.rows.length; all.indeterminate = picked > 0 && !all.checked;
      all.disabled = this.busy || !this.rows.length;
      all.onchange = () => {for (const n of this.rows) all.checked ? this.selected.add(n.id) : this.selected.delete(n.id); this.render();};
      if (this.selected.size) {
        // GitHub-style: with a selection, the toolbar holds the bulk actions.
        const count = document.createElement('span'); count.textContent = `${this.selected.size} selected`;
        const ids = () => [...this.selected];
        bar.append(all, count, ...(this.state === 'archived'
          ? [this.button('move_to_inbox', 'Move selected to inbox', () => void this.action('unarchive', ids()), true)]
          : [this.button('mark_email_read', 'Mark selected as read', () => void this.action('read', ids()), true),
            this.button('check', 'Mark selected as done', () => void this.action('archive', ids()), true)]), grow);
      } else {
        bar.append(all, type, grow);
        if (this.state === 'all' || this.state === 'unread') {
          const unread = this.button('filter_list', 'Unread', () => {this.state = this.state === 'unread' ? 'all' : 'unread'; this.resetPaging(); void this.refresh();}, true);
          unread.setAttribute('aria-pressed', String(this.state === 'unread'));
          bar.insertBefore(unread, grow);
          bar.append(this.button('done_all', 'Mark all as read', () => void this.action('read-all'), true));
        }
      }
      if (this.rows.length || this.pageIndex) {
        const range = document.createElement('span'); range.className = 'range';
        range.textContent = `${this.pageIndex * pageSize + 1}–${this.pageIndex * pageSize + this.rows.length}`;
        const prev = this.button('chevron_left', 'Previous page', () => {this.pageIndex--; this.selected.clear(); void this.refresh();});
        prev.disabled ||= this.pageIndex === 0;
        const next = this.button('chevron_right', 'Next page', () => {
          this.cursors[this.pageIndex + 1] = this.next; this.pageIndex++; this.selected.clear(); void this.refresh();
        });
        next.disabled ||= !this.next;
        bar.append(range, prev, next);
      }
      bar.append(this.button('refresh', 'Refresh', () => void this.refresh()));
      panel.append(bar);
      for (const n of this.rows) {
        const row = document.createElement('div'); row.className = n.read_at ? 'row' : 'row unread';
        if (this.selected.has(n.id)) row.classList.add('selected');
        const pick = document.createElement('input'); pick.type = 'checkbox'; pick.checked = this.selected.has(n.id);
        pick.setAttribute('aria-label', `Select ${n.project} #${n.change}`); pick.disabled = this.busy;
        pick.dataset.focus = `select-${n.id}`;
        pick.onchange = () => {pick.checked ? this.selected.add(n.id) : this.selected.delete(n.id); this.render();};
        const dot = document.createElement('span'); dot.className = 'dot';
        const [kindIcon, reason] = types[n.type] ?? ['notifications', n.type];
        const kind = icon(kindIcon); kind.className = 'kind'; kind.title = reason;
        const main = document.createElement('div'); main.className = 'main';
        const a = document.createElement('a'); a.className = 'title'; a.dataset.change = String(n.change);
        if (/^\/c\//.test(n.url)) a.href = this.rootUrl(n.url);
        a.textContent = `${n.project} #${n.change}`;
        if (!n.read_at) a.onclick = () => void this.markOpened(n);
        const meta = document.createElement('div'); meta.className = 'meta'; meta.dataset.actor = String(n.actor); meta.dataset.reason = reason;
        meta.textContent = `${reason} · ${actorName(n.actor)}`;
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
        row.append(pick, dot, kind, main, when, actions); panel.append(row);
      }
      if (!this.rows.length) {const e = document.createElement('p'); e.className = 'empty'; e.setAttribute('role', 'status'); e.textContent = this.busy ? 'Loading…' : this.message || 'All caught up!'; panel.append(e);}
      else if (this.message) {const e = document.createElement('p'); e.setAttribute('role', 'status'); e.className = 'empty'; e.textContent = this.message; panel.append(e);}
    } else {
      const e = document.createElement('p'); e.className = 'empty'; e.setAttribute('role', 'status'); e.textContent = this.message; panel.append(e);
      if (!this.busy) panel.append(this.button('refresh', 'Retry', () => void this.refresh(), true));
    }
    root.append(layout);
    if (focusKey) root.querySelector<HTMLElement>(`[data-focus="${focusKey}"]`)?.focus();
  }
}
class Bell extends Inbox {protected override bellOnly = true;}
customElements.define('notification-center-screen', Inbox);
customElements.define('notification-center-bell', Bell);
Gerrit.install(plugin => {
  plugin.screen('notifications', 'notification-center-screen');
  plugin.registerCustomComponent('header-top-right', 'notification-center-bell');
});
