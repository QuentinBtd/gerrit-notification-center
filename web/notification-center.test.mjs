import {test} from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {Window} from 'happy-dom';

const settle=()=>new Promise(r=>setTimeout(r,10));
async function harness({loggedIn=true,fail=false}={}) {
  const bundle=await build({entryPoints:['web/notification-center.ts'],bundle:true,format:'iife',write:false});
  const w=new Window({url:'https://review.example.org/gerrit/'});
  const calls=[],registrations=[];
  let callback;
  const rows=[{id:2,account_id:7,type:'MENTION',actor:8,project:'<img src=x onerror=alert(1)>',change:12,url:'/c/p/+/12',created_at:100,read_at:null,archived_at:null}];
  const api={getLoggedIn:async()=>loggedIn,get:async url=>{
    calls.push(['get',url]); if(fail) throw Error('private backend detail');
    return url.includes('count=')?{unread:3,pollSeconds:45}:{items:rows,next:null};
  },post:async(url,payload)=>{calls.push(['post',url,payload]);}};
  const plugin={url:()=> 'https://review.example.org/gerrit/',restApi:()=>api,
    screen:(...a)=>registrations.push(['screen',...a]),
    registerCustomComponent:(...a)=>registrations.push(['component',...a])};
  w.Gerrit={install:cb=>{callback=cb;}};
  w.eval(bundle.outputFiles[0].text); callback(plugin);
  const screen=w.document.createElement('notification-center-screen');screen.plugin=plugin;
  w.document.body.append(screen);await settle();
  return {w,screen,calls,registrations,plugin};
}

test('official registration and full inbox actions use authenticated Gerrit REST',async()=>{
  const {w,screen,calls,registrations}=await harness();
  try {
    assert.ok(registrations.some(r=>r[0]==='screen'&&r[1]==='notifications'));
    assert.ok(registrations.some(r=>r[1]==='footer-left'));
    const root=screen.shadowRoot;
    assert.match(root.textContent,/Notifications/);
    assert.match(root.textContent,/<img src=x/);
    assert.equal(root.querySelector('img'),null);
    assert.equal(root.querySelector('a[data-change]').href,'https://review.example.org/gerrit/c/p/+/12');
    const read=[...root.querySelectorAll('button')].find(b=>b.textContent==='Mark read');
    read.click();await settle();
    assert.deepEqual(JSON.parse(JSON.stringify(calls.find(c=>c[0]==='post')[2])),{action:'read',id:2});
    [...root.querySelectorAll('button')].find(b=>b.textContent==='Archive').click();await settle();
    assert.ok(calls.some(c=>c[2]?.action==='archive'));
    [...root.querySelectorAll('button')].find(b=>b.textContent==='Mark all read').click();await settle();
    assert.ok(calls.some(c=>c[2]?.action==='read-all'));
    const state=root.querySelector('select[aria-label="State"]');state.value='archived';state.dispatchEvent(new w.Event('change'));await settle();
    assert.ok(calls.some(c=>c[1].includes('state=archived')));
  } finally {await w.happyDOM.close();}
});

test('anonymous users never query inbox; backend failures reveal no raw payload',async()=>{
  const a=await harness({loggedIn:false});
  try {assert.equal(a.calls.length,0);assert.match(a.screen.shadowRoot.textContent,/Sign in/);} finally {await a.w.happyDOM.close();}
  const b=await harness({fail:true});
  try {assert.match(b.screen.shadowRoot.textContent,/Unable to load/);assert.doesNotMatch(b.screen.shadowRoot.textContent,/private backend detail/);} finally {await b.w.happyDOM.close();}
});
