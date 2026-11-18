import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const workflow=()=>readFileSync('.github/workflows/ci.yml','utf8');
async function request(overrides={}) {
  const script=workflow().split('// BEGIN REQUEST')[1].split('// END REQUEST')[0];
  const output={};let checkouts=0;
  const context={repo:{owner:'o',repo:'r'},issue:{number:1},runAttempt:1,payload:{comment:{body:'/test',author_association:'OWNER',user:{login:'owner'}},issue:{pull_request:{}}},...overrides};
  const pr={state:overrides.state??'open',head:{sha:'a'.repeat(40),repo:overrides.deleted?null:{full_name:'o/r'}}};
  const github={rest:{repos:{getCollaboratorPermissionLevel:async()=>({data:{permission:overrides.permission??'write'}})},pulls:{get:async()=>({data:pr})}}};
  const core={setOutput:(k,v)=>output[k]=v,notice:()=>{}};
  await new (Object.getPrototypeOf(async function(){}).constructor)('github','context','core',script)(github,context,core);
  return output;
}
test('only exact trusted authorized live PR requests capture immutable heads',async()=>{
  assert.equal((await request()).sha,'a'.repeat(40));
  for(const body of ['/test extra','please /test','/tests','/test\nextra']) assert.deepEqual(await request({payload:{comment:{body,author_association:'OWNER',user:{login:'o'}},issue:{pull_request:{}}}}),{});
  assert.equal((await request({payload:{comment:{body:' \r\n/test \r\n',author_association:'OWNER',user:{login:'o'}},issue:{pull_request:{}}}})).sha,'a'.repeat(40));
  for(const x of [{permission:'read'},{state:'closed'},{deleted:true},{runAttempt:2},{payload:{comment:{body:'/test',author_association:'CONTRIBUTOR',user:{login:'x'}},issue:{pull_request:{}}}},{payload:{comment:{body:'/test',author_association:'OWNER',user:{login:'o'}},issue:{}}}]) assert.deepEqual(await request(x),{});
});
test('tests are comment-only and checkout untrusted code without write token or secrets',()=>{
  const text=workflow();
  assert.match(text,/issue_comment:/);assert.doesNotMatch(text,/pull_request:\s*\n|workflow_dispatch:|push:/);
  assert.match(text,/permissions: \{\}/);assert.match(text,/persist-credentials: false/);
  assert.match(text,/ref: \$\{\{ needs.request.outputs.sha \}\}/);
  assert.match(text,/contents: read/);
});
