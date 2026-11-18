import {test} from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {Window} from 'happy-dom';

const btn=(root,label)=>root.querySelector(`button[aria-label="${label}"]`);
const settle=()=>new Promise(r=>setTimeout(r,10));
async function harness({loggedIn=true,fail=false,failSave=false,fakePolling=false,stateful=false}={}) {
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
    if(url.includes('count='))return {unread:3,pollSeconds:45};
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
