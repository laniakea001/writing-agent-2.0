import test from 'node:test';
import assert from 'node:assert/strict';
import {workbenchLocation} from './workbenchLocation.mjs';
const storage={getItem:key=>key.endsWith('workspace')?'old-space':'old-project'};
test('a complete continuation link does not mix with a previously selected article',()=>{
  assert.deepEqual(workbenchLocation('?workspace=new-space&project=new-project',storage),{workspace:'new-space',project:'new-project'});
});
test('an incomplete link cannot combine another workspace with the saved project',()=>{
  assert.deepEqual(workbenchLocation('?workspace=new-space',storage),{workspace:'old-space',project:'old-project'});
});
