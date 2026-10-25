import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root=new URL('../app/src/main/java/com/markreader/',import.meta.url);
const read=name=>readFileSync(new URL(name,root),'utf8');
test('production editing surface is native and viewport bounded, not a full Compose text value',()=>{
 const screen=read('ui/screens/EditorScreen.kt');
 assert.match(screen,/NativeDocumentEditor\(/);
 assert.doesNotMatch(screen,/BasicTextField\(|textFieldValue|\.verticalScroll\(/);
 const editor=read('ui/editor/NativeDocumentEditor.kt');
 assert.match(editor,/AndroidView\(/);
 assert.match(editor,/onRelease\s*=\s*\{\s*it.unbind\(\)/);
 assert.match(editor,/view.bind\(session\)/);
});
test('typing callback performs no document snapshot or UI string work',()=>{
 const native=read('ui/editor/NativeDocumentEditText.kt');
 const watcher=native.slice(native.indexOf('private val watcher'),native.indexOf('\n    init'));
 assert.match(watcher,/onNativeTextChanged\(/);
 assert.doesNotMatch(watcher,/toString\(|snapshot\(|TextFieldValue|setText\(/);
 const vm=read('ui/screens/EditorViewModel.kt');
 assert.doesNotMatch(vm,/TextFieldValue|textFieldValue/);
 const callback=vm.slice(vm.indexOf('documentSession.onChanged ='),vm.indexOf('observePreferences()'));
 assert.doesNotMatch(callback,/snapshot\(|toString\(|loadEditorPreview\(/);
 assert.match(callback,/if \(!_uiState.value.isModified(?: \|\| _uiState.value.previewText != null)?\)/);
});
test('session snapshots are explicit and save snapshots are captured before launching IO',()=>{
 const vm=read('ui/screens/EditorViewModel.kt');
 const save=vm.slice(vm.indexOf('private fun saveSnapshot'),vm.indexOf('fun onSaveResultConsumed'));
 assert.equal([...save.matchAll(/documentSession.snapshot\(\)/g)].length,1);
 assert.ok(save.indexOf('documentSession.snapshot()')<save.indexOf('viewModelScope.launch'));
 assert.match(save,/revisions.finishSave\(written\)/);
 assert.match(save,/revisions.isModified\(documentSession.revision\)/);
});
