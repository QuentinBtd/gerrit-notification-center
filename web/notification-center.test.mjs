import {test} from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {Window} from 'happy-dom';

const btn=(root,label)=>root.querySelector(`button[aria-label="${label}"]`);
const settle=()=>new Promise(r=>setTimeout(r,10));
async function harness({loggedIn=true,fail=false,failSave=false,fakePolling=false,stateful=false,count={unread:3,pollSeconds:45},prefs={email:false,browser:false,subscriptions:[]},setup=()=>{}}={}) {
  const bundle=await build({entryPoints:['web/notification-center.ts'],bundle:true,format:'iife',write:false});
  const w=new Window({url:'https://review.example.org/gerrit/'});
  const calls=[],registrations=[],timers=new Map();
  let timerId=0;
  if(fakePolling) {
    w.setTimeout=(callback,delay)=>{const id=++timerId;timers.set(id,{callback,delay});return id;};
    w.clearTimeout=id=>timers.delete(id);
  }
  let callback,gate=null;
  const rows=[{id:2,account_id:7,type:'MENTION',actor:8,project:'<img src=x onerror=alert(1)>',change:12,url:'/c/p/+/12',created_at:100,read_at:null,archived_at:null}];
  const api={getLoggedIn:async()=>loggedIn,get:async url=>{
    calls.push(['get',url]); if(fail) throw Error('private backend detail');
    if(url.includes('count='))return count;
    const account=url.match(/^\/accounts\/(\d+)$/);
    if(account)return account[1]==='8'?{_account_id:8,name:'Ada Lovelace',username:'ada'}:Promise.reject(Error('404'));
    if(url.includes('~preferences'))return JSON.parse(JSON.stringify(prefs));
    if(!stateful)return {items:rows,next:null};
    const state=new URLSearchParams(url.split('?')[1]).get('state');
    const keep={all:r=>!r.archived_at,unread:r=>!r.archived_at&&!r.read_at,saved:r=>r.saved_at,archived:r=>r.archived_at}[state];
    // Snapshot copies before an optional hold, like a real response computed server-side earlier.
    const page={items:rows.filter(keep).map(r=>({...r})),next:null};
    if(gate)await gate;
    return page;
  },post:async(url,payload)=>{
    calls.push(['post',url,payload]);if(failSave) throw Error('private mutation detail');
    throw Error('Response payload is not prefixed json. Payload: ');
  },fetch:async(method,url,payload)=>{
    if(method==='PUT') {
      calls.push(['put',url,JSON.parse(JSON.stringify(payload))]);
      if(payload.subscriptions.some(x=>x.query==='bad'))return new w.Response('invalid query "bad": unsupported operator',{status:400});
      prefs=JSON.parse(JSON.stringify(payload));return new w.Response(null,{status:200});
    }
    assert.equal(method,'POST');calls.push(['post',url,payload]);
    if(stateful&&!failSave) {
      const [field,value]={read:['read_at',1],unread:['read_at',null],archive:['archived_at',1],unarchive:['archived_at',null],save:['saved_at',1],unsave:['saved_at',null]}[payload.action];
      rows.find(r=>r.id===payload.id)[field]=value;
    }
    return new w.Response(failSave?'private mutation detail':null,{status:failSave?500:204});
  }};
  const plugin={url:()=> 'https://review.example.org/gerrit/plugins/notification-center/',restApi:()=>api,
    screen:(...a)=>registrations.push(['screen',...a]),
    registerCustomComponent:(...a)=>registrations.push(['component',...a])};
  w.Gerrit={install:cb=>{callback=cb;}};
  setup(w);
  w.eval(bundle.outputFiles[0].text); callback(plugin);
  const screen=w.document.createElement('notification-center-screen');screen.plugin=plugin;
  w.document.body.append(screen);await settle();
  const hold=()=>{let release;gate=new Promise(r=>{release=()=>{gate=null;r();};});return release;};
  return {w,screen,calls,registrations,plugin,timers,hold};
}

test('official registration and full inbox actions use authenticated Gerrit REST',async()=>{
  const {w,screen,calls,registrations,plugin}=await harness();
  try {
    assert.ok(registrations.some(r=>r[0]==='screen'&&r[1]==='notifications'));
    assert.ok(registrations.some(r=>r[1]==='header-top-right'));
    const root=screen.shadowRoot;
    assert.match(root.textContent,/Inbox/);assert.match(root.textContent,/Saved/);assert.match(root.textContent,/Done/);
    assert.match(root.textContent,/Mentioned/);
    assert.match(root.textContent,/Mentioned · Ada Lovelace/);
    assert.doesNotMatch(root.textContent,/Account 8/);
    assert.match(root.textContent,/<img src=x/);
    assert.equal(root.querySelector('img'),null);
    assert.equal(root.querySelector('a[data-change]').href,'https://review.example.org/gerrit/c/p/+/12');
    const bell=w.document.createElement('notification-center-bell');bell.plugin=plugin;
    w.document.body.append(bell);await settle();
    assert.equal(bell.shadowRoot.querySelector('a').href,'https://review.example.org/gerrit/x/notification-center/notifications');
    assert.equal(bell.shadowRoot.querySelector('gr-icon').getAttribute('icon'),'notifications');
    assert.equal(bell.shadowRoot.querySelector('.badge').textContent,'3');
    const read=btn(root,'Mark as read');
    read.click();await settle();
    assert.deepEqual(JSON.parse(JSON.stringify(calls.find(c=>c[0]==='post')[2])),{action:'read',id:2});
    btn(root,'Mark as done').click();await settle();
    assert.ok(calls.some(c=>c[2]?.action==='archive'));
    btn(root,'Mark all as read').click();await settle();
    assert.ok(calls.some(c=>c[2]?.action==='read-all'));
    btn(root,'Save').click();await settle();
    assert.ok(calls.some(c=>c[2]?.action==='save'));
    btn(root,'Saved').click();await settle();
    assert.ok(calls.some(c=>c[1].includes('state=saved')));
    assert.equal(btn(root,'Saved').getAttribute('aria-current'),'page');
    btn(root,'Done').click();await settle();
    assert.ok(calls.some(c=>c[1].includes('state=archived')));
  } finally {await w.happyDOM.close();}
});

test('empty 204 mutation response refreshes without a false save failure',async()=>{
  const {w,screen,calls,timers}=await harness({fakePolling:true});
  try {
    const reads=calls.filter(c=>c[0]==='get').length;
    btn(screen.shadowRoot,'Mark as read').click();
    await settle();
    assert.doesNotMatch(screen.shadowRoot.textContent,/Unable to save/);
    assert.equal(calls.filter(c=>c[0]==='post').length,1);
    assert.equal(calls.filter(c=>c[0]==='get').length,reads+2);
    assert.equal(timers.size,1);
  } finally {await w.happyDOM.close();}
});

test('failed save resumes bounded read polling without replaying mutation; detach cancels it',async()=>{
  const {w,screen,calls,timers}=await harness({failSave:true,fakePolling:true});
  try {
    assert.equal(timers.size,1);
    assert.ok(screen.shadowRoot.querySelector('.row.unread'));
    btn(screen.shadowRoot,'Mark as read').click();
    await settle();
    assert.match(screen.shadowRoot.textContent,/Unable to save/);
    assert.doesNotMatch(screen.shadowRoot.textContent,/private mutation detail/);
    assert.equal(calls.filter(c=>c[0]==='post').length,1);
    assert.equal(timers.size,1,'failed save must rearm polling');
    const [id,timer]=[...timers][0];
    assert.equal(timer.delay,45000);
    const reads=calls.filter(c=>c[0]==='get').length;
    timers.delete(id);timer.callback();await settle();
    assert.equal(calls.filter(c=>c[0]==='get').length,reads+2);
    assert.equal(calls.filter(c=>c[0]==='post').length,1,'poll must not retry the save');
    assert.ok(screen.shadowRoot.querySelector('.row.unread'));
    assert.equal(timers.size,1);
    screen.remove();
    assert.equal(timers.size,0);
  } finally {await w.happyDOM.close();}
});

test('anonymous users never query inbox; backend failures reveal no raw payload',async()=>{
  const a=await harness({loggedIn:false});
  try {assert.equal(a.calls.length,0);assert.match(a.screen.shadowRoot.textContent,/Sign in/);} finally {await a.w.happyDOM.close();}
  const b=await harness({fail:true});
  try {assert.match(b.screen.shadowRoot.textContent,/Unable to load/);assert.doesNotMatch(b.screen.shadowRoot.textContent,/private backend detail/);} finally {await b.w.happyDOM.close();}
});

test('Unread filter, Save/Unsave and Done/Move to inbox round-trip',async()=>{
  const {w,screen,calls}=await harness({stateful:true});
  const root=screen.shadowRoot,post=()=>JSON.parse(JSON.stringify(calls.filter(c=>c[0]==='post').at(-1)[2]));
  try {
    btn(root,'Unread').click();await settle();
    assert.ok(calls.at(-1)[1].includes('state=unread'));
    assert.equal(btn(root,'Unread').getAttribute('aria-pressed'),'true');
    assert.equal(btn(root,'Inbox').getAttribute('aria-current'),'page');
    assert.ok(root.querySelector('.row.unread'));
    btn(root,'Mark as read').click();await settle();
    assert.equal(root.querySelector('.row'),null,'read rows leave the Unread filter');
    btn(root,'Unread').click();await settle();
    assert.equal(btn(root,'Unread').getAttribute('aria-pressed'),'false');
    btn(root,'Save').click();await settle();
    assert.deepEqual(post(),{action:'save',id:2});
    btn(root,'Saved').click();await settle();
    btn(root,'Unsave').click();await settle();
    assert.deepEqual(post(),{action:'unsave',id:2});
    assert.equal(root.querySelector('.row'),null);
    btn(root,'Inbox').click();await settle();
    btn(root,'Mark as done').click();await settle();
    assert.equal(root.querySelector('.row'),null);
    btn(root,'Done').click();await settle();
    btn(root,'Move to inbox').click();await settle();
    assert.deepEqual(post(),{action:'unarchive',id:2});
    assert.equal(root.querySelector('.row'),null);
    btn(root,'Inbox').click();await settle();
    assert.ok(root.querySelector('.row'));
  } finally {await w.happyDOM.close();}
});

test('opening an unread notification marks it read and surfaces a failed save',async()=>{
  const ok=await harness({stateful:true});
  try {
    const a=ok.screen.shadowRoot.querySelector('a[data-change]');
    a.addEventListener('click',e=>e.preventDefault()); // keep happy-dom from navigating
    a.click();await settle();
    assert.deepEqual(JSON.parse(JSON.stringify(ok.calls.find(c=>c[0]==='post')[2])),{action:'read',id:2});
    assert.equal(ok.screen.shadowRoot.querySelector('.row.unread'),null);
  } finally {await ok.w.happyDOM.close();}
  const bad=await harness({failSave:true});
  try {
    const a=bad.screen.shadowRoot.querySelector('a[data-change]');
    a.addEventListener('click',e=>e.preventDefault());
    a.click();await settle();
    assert.match(bad.screen.shadowRoot.textContent,/Unable to save/);
    assert.doesNotMatch(bad.screen.shadowRoot.textContent,/private mutation detail/);
  } finally {await bad.w.happyDOM.close();}
});

test('opening an unread notification during a pending refresh still marks it read',async()=>{
  const {w,screen,calls,hold}=await harness({stateful:true});
  const root=screen.shadowRoot;
  try {
    const release=hold(); // holds the list response, already computed as unread
    btn(root,'Refresh').click();await settle();
    assert.ok(btn(root,'Refresh').disabled,'refresh is pending');
    const a=root.querySelector('a[data-change]');
    a.addEventListener('click',e=>e.preventDefault());
    a.click();await settle();
    assert.deepEqual(JSON.parse(JSON.stringify(calls.find(c=>c[0]==='post')[2])),{action:'read',id:2});
    release();await settle();
    assert.equal(root.querySelector('.row.unread'),null);
  } finally {await w.happyDOM.close();}
});

test('Settings tab edits delivery channels and query subscriptions without polling',async()=>{
  const {w,screen,calls,timers}=await harness({fakePolling:true,prefs:{email:false,browser:false,subscriptions:[{query:'project:a',types:['MERGED']}]}});
  const root=screen.shadowRoot;
  try {
    btn(root,'Settings').click();await settle();
    assert.ok(calls.some(c=>c[1].includes('notification-center~preferences')));
    assert.equal(timers.size,0,'no polling while editing settings');
    assert.equal(root.querySelector('input[aria-label="Subscription query"]').value,'project:a');
    assert.match(root.textContent,/Gerrit watched projects/);
    root.querySelector('label input[type=checkbox]').click(); // Email
    btn(root,'Add subscription').click();
    const queries=root.querySelectorAll('input[aria-label="Subscription query"]');
    queries[1].value='bad';queries[1].dispatchEvent(new w.Event('input'));
    btn(root,'Save settings').click();await settle();
    assert.match(root.textContent,/invalid query "bad"/);
    const q=root.querySelectorAll('input[aria-label="Subscription query"]')[1];
    q.value='project:^team/.* branch:main';q.dispatchEvent(new w.Event('input'));
    btn(root,'Save settings').click();await settle();
    const put=calls.filter(c=>c[0]==='put').at(-1)[2];
    assert.equal(put.email,true);
    assert.deepEqual(put.subscriptions[1],{query:'project:^team/.* branch:main',types:['NEW_CHANGE','PATCHSET','COMMENT','MERGED','ABANDONED']});
    assert.match(root.textContent,/Saved\./);
    btn(root,'Remove subscription').click();
    assert.equal(root.querySelectorAll('input[aria-label="Subscription query"]').length,1);
  } finally {await w.happyDOM.close();}
});

test('bell shows browser notifications only for new unread items once permission is granted',async()=>{
  const shown=[];
  const setup=w=>{w.Notification=class {constructor(title,o){shown.push([title,o.tag]);} static permission='granted';};};
  const count={unread:1,latest:2,pollSeconds:45,browser:true};
  const {w,plugin,calls}=await harness({count,setup});
  try {
    const bell=()=>{const b=w.document.createElement('notification-center-bell');b.plugin=plugin;w.document.body.append(b);return b;};
    bell();await settle();
    assert.equal(shown.length,0,'first poll only records a baseline');
    assert.equal(w.localStorage.getItem('notification-center-last-browser-id'),'2');
    w.localStorage.setItem('notification-center-last-browser-id','1');
    bell();await settle();
    assert.deepEqual(shown,[['Mentioned: <img src=x onerror=alert(1)> #12','notification-center-2']]);
    bell();await settle();
    assert.equal(shown.length,1,'already shown items are not repeated');
    count.browser=false;w.localStorage.setItem('notification-center-last-browser-id','1');
    const before=calls.length;bell();await settle();
    assert.equal(shown.length,1);assert.ok(!calls.slice(before).some(c=>c[1].includes('state=unread')));
  } finally {await w.happyDOM.close();}
});

test('first arrival after an empty-inbox baseline is shown',async()=>{
  const shown=[];
  const setup=w=>{w.Notification=class {constructor(title,o){shown.push(o.tag);} static permission='granted';};};
  const count={unread:0,latest:0,pollSeconds:45,browser:true};
  const {w,plugin}=await harness({count,setup});
  try {
    const bell=()=>{const b=w.document.createElement('notification-center-bell');b.plugin=plugin;w.document.body.append(b);};
    bell();await settle();
    assert.equal(w.localStorage.getItem('notification-center-last-browser-id'),'0');
    Object.assign(count,{unread:1,latest:2});
    bell();await settle();
    assert.deepEqual(shown,['notification-center-2']);
  } finally {await w.happyDOM.close();}
});

test('a failed detail fetch is retried on the next poll instead of being lost',async()=>{
  const shown=[];
  const setup=w=>{
    w.Notification=class {constructor(title,o){shown.push(o.tag);} static permission='granted';};
    w.localStorage.setItem('notification-center-last-browser-id','1');
  };
  const {w,plugin}=await harness({count:{unread:1,latest:2,pollSeconds:45,browser:true},setup});
  try {
    const api=plugin.restApi(),get=api.get;let failOnce=true;
    api.get=async url=>{if(url.includes('state=unread')&&failOnce){failOnce=false;throw Error('network');}return get(url);};
    const bell=()=>{const b=w.document.createElement('notification-center-bell');b.plugin=plugin;w.document.body.append(b);return b;};
    const b=bell();await settle();
    assert.equal(shown.length,0);
    assert.equal(w.localStorage.getItem('notification-center-last-browser-id'),'1','not advanced on failure');
    assert.ok(b.shadowRoot.querySelector('.badge'),'bell keeps working after a failed fetch');
    bell();await settle();
    assert.deepEqual(shown,['notification-center-2']);
    assert.equal(w.localStorage.getItem('notification-center-last-browser-id'),'2');
  } finally {await w.happyDOM.close();}
});

test('an item arriving between the count and the detail fetch is shown once',async()=>{
  const shown=[];
  const setup=w=>{
    w.Notification=class {constructor(title,o){shown.push(o.tag);} static permission='granted';};
    w.localStorage.setItem('notification-center-last-browser-id','1');
  };
  const count={unread:1,latest:2,pollSeconds:45,browser:true};
  const {w,plugin}=await harness({count,setup});
  try {
    const api=plugin.restApi(),get=api.get;
    const newer={id:3,account_id:7,type:'MERGED',actor:8,project:'p',change:13,url:'/c/p/+/13',created_at:200};
    api.get=async url=>{
      if(!url.includes('state=unread'))return get(url);
      const page=await get(url);return {...page,items:[newer,...page.items]};
    };
    const bell=()=>{const b=w.document.createElement('notification-center-bell');b.plugin=plugin;w.document.body.append(b);};
    bell();await settle(); // count says 2, details already contain 3
    assert.deepEqual(shown,['notification-center-2','notification-center-3']);
    assert.equal(w.localStorage.getItem('notification-center-last-browser-id'),'3');
    count.latest=3;
    bell();await settle();
    assert.equal(shown.length,2,'3 is not displayed again');
  } finally {await w.happyDOM.close();}
});

test('unresolvable actors keep their account ID and are fetched only once',async()=>{
  const {w,plugin,calls}=await harness();
  try {
    const api=plugin.restApi(),get=api.get;
    api.get=async url=>{
      if(url.includes('state=')&&!url.includes('count=')){calls.push(['get',url]);return {items:[{id:5,type:'MERGED',actor:99,project:'p',change:3,url:'/c/p/+/3',created_at:100}]};}
      return get(url);
    };
    const open=async()=>{const s=w.document.createElement('notification-center-screen');s.plugin=plugin;w.document.body.append(s);await settle();return s;};
    const s=await open();
    assert.match(s.shadowRoot.textContent,/Merged · Account 99/);
    await open();
    assert.equal(calls.filter(c=>c[1]==='/accounts/99').length,1);
  } finally {await w.happyDOM.close();}
});

test('bell badge follows screen changes and tab focus without waiting for its poll',async()=>{
  const count={unread:3,pollSeconds:45};
  const {w,screen,plugin,calls}=await harness({count,fakePolling:true});
  try {
    const bell=w.document.createElement('notification-center-bell');bell.plugin=plugin;
    w.document.body.append(bell);await settle();
    assert.equal(bell.shadowRoot.querySelector('.badge').textContent,'3');
    count.unread=2;
    btn(screen.shadowRoot,'Mark as read').click();await settle();
    assert.equal(bell.shadowRoot.querySelector('.badge').textContent,'2','updated right after the change');
    count.unread=5;
    w.document.dispatchEvent(new w.Event('visibilitychange'));await settle();
    assert.equal(bell.shadowRoot.querySelector('.badge').textContent,'5','updated when the tab is shown again');
    bell.remove();count.unread=1;
    const before=calls.length;
    btn(screen.shadowRoot,'Mark as read').click();await settle();
    // Only the screen's own refresh reads the count: a detached bell no longer listens.
    assert.equal(calls.slice(before).filter(c=>c[1].includes('count=')).length,1);
  } finally {await w.happyDOM.close();}
});

test('a slow actor-name lookup never blocks the inbox',async()=>{
  const {w,plugin}=await harness();
  try {
    const api=plugin.restApi(),get=api.get;let release;
    api.get=async url=>{
      if(url.includes('state=')&&!url.includes('count='))return {items:[{id:6,type:'MERGED',actor:77,project:'p',change:4,url:'/c/p/+/4',created_at:100}]};
      if(url==='/accounts/77'){await new Promise(r=>{release=r;});return {name:'Grace Hopper'};}
      return get(url);
    };
    const s=w.document.createElement('notification-center-screen');s.plugin=plugin;w.document.body.append(s);await settle();
    const root=s.shadowRoot;
    assert.ok(root.querySelector('.row'),'rows shown while the name is pending');
    assert.match(root.textContent,/Merged · Account 77/);
    assert.equal(btn(root,'Mark as read').disabled,false,'buttons usable while the name is pending');
    release();await settle();
    assert.match(root.textContent,/Merged · Grace Hopper/);
  } finally {await w.happyDOM.close();}
});

test('a late actor-name lookup does not disturb Settings editing',async()=>{
  const {w,plugin}=await harness();
  try {
    const api=plugin.restApi(),get=api.get;let release;
    api.get=async url=>{
      if(url.includes('state=')&&!url.includes('count='))return {items:[{id:6,type:'MERGED',actor:78,project:'p',change:4,url:'/c/p/+/4',created_at:100}]};
      if(url.includes('~preferences'))return {email:false,browser:false,subscriptions:[{query:'project:a',types:['MERGED']}]};
      if(url==='/accounts/78'){await new Promise(r=>{release=r;});return {name:'Grace Hopper'};}
      return get(url);
    };
    const s=w.document.createElement('notification-center-screen');s.plugin=plugin;w.document.body.append(s);await settle();
    const root=s.shadowRoot;
    btn(root,'Settings').click();await settle();
    const input=root.querySelector('input[aria-label="Subscription query"]');
    input.focus();input.value='project:a branch:ma';input.dispatchEvent(new w.Event('input'));
    release();await settle();
    assert.ok(input.isConnected,'focused input is not replaced');
    assert.equal(root.activeElement,input);
    assert.equal(input.value,'project:a branch:ma');
    btn(root,'Inbox').click();await settle();
    assert.match(root.textContent,/Merged · Grace Hopper/,'resolved name used once back in the inbox');
  } finally {await w.happyDOM.close();}
});

test('replacing Gerrit emails is only offered with email delivery and is saved',async()=>{
  const {w,screen,calls}=await harness();
  const root=screen.shadowRoot;
  try {
    btn(root,'Settings').click();await settle();
    const box=label=>[...root.querySelectorAll('label')].find(l=>l.textContent.startsWith(label)).querySelector('input');
    assert.equal(box('Replace Gerrit').disabled,true,'needs email first');
    box('Email').click();await settle();
    assert.equal(box('Replace Gerrit').disabled,false);
    box('Replace Gerrit').click();
    btn(root,'Save settings').click();await settle();
    let put=calls.filter(c=>c[0]==='put').at(-1)[2];
    assert.equal(put.email,true);assert.equal(put.replace_gerrit_emails,true);
    box('Email').click();await settle(); // turning email off also turns replacing off
    assert.equal(box('Replace Gerrit').checked,false);
    btn(root,'Save settings').click();await settle();
    put=calls.filter(c=>c[0]==='put').at(-1)[2];
    assert.equal(put.replace_gerrit_emails,false);
  } finally {await w.happyDOM.close();}
});
