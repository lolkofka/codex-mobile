'use strict';
const checks=[],requests=[];
window.Phone={post(source){const p=JSON.parse(source);requests.push(p);if(p.action==='send')window.submissionResult({accepted:true,value:p.value});}};
const assert=(value,message)=>{if(!value)throw new Error(message);checks.push(message);};
const clickOption=text=>Array.from(document.querySelectorAll('#sheet-content .option')).find(b=>b.querySelector('strong')?.textContent===text).click();
window.addEventListener('error',e=>{const p=document.createElement('pre');p.id='test-failure';p.textContent=e.message;document.body.append(p);});
setTimeout(()=>{
 try{
  document.documentElement.style.width='360px';$('app').style.width='360px';
  const fixture='00000000-0000-0000-0000-000000000001.png';
  applyState({replace:true,ready:true,busy:false,loggedIn:true,model:'test-model',effort:'high',status:'Готов',account:'Тест',target:'phone',models:[{id:'test-model',model:'test-model',displayName:'Тестовая модель',isDefault:true,supportedReasoningEfforts:[{reasoningEffort:'high'}]}],order:[],updates:[],outbox:[],modelsLoading:false,modelsError:''});
  $('attach').click();clickOption('Выбрать из галереи');assert(requests.at(-1).action==='pickImages','gallery opens via native bridge');
  $('attach').click();clickOption('Вставить из буфера');assert(requests.at(-1).action==='pasteClipboard','clipboard import uses native bridge');
  $('attach').click();clickOption('Создать изображение');assert($('prompt').value.startsWith('Создай изображение:'),'image generation prompt is editable');$('prompt').value='';
  setAttachments({images:[{id:fixture,url:'media-fixture.png'}],loading:false});assert(!$('send').hidden&&!$('send').disabled,'image-only message can be sent');$('send').click();assert(requests.some(r=>r.action==='send'&&r.value===''),'image-only submission reaches native bridge');
  setAttachments({images:[{id:fixture,url:'media-fixture.png'}],loading:false});document.querySelector('[data-remove-attachment]').click();assert(requests.at(-1).action==='removeAttachment','attachment removal is explicit');
  setAttachments({images:[],loading:true});assert($('send').disabled,'submission waits for image import');setAttachments({images:[],loading:false});
  const links=inline('[веб](https://example.org/a_(b)?x=1&y=2) [файл](</sdcard/My Folder/report.pdf:12>) https://example.org/plain. /data/adb/avr-codex/workspace/demo.txt [bad](javascript:alert(1)) <img src=x onerror=alert(1)>');
  const box=document.createElement('article');box.innerHTML=links;$('messages').append(box);
  assert(box.querySelectorAll('a[data-link]').length===4,'web, bare URL and local paths are clickable');
  assert(box.querySelector('[data-link="/sdcard/My Folder/report.pdf:12"]')!==null,'local path with spaces and line number is preserved');
  assert(!box.querySelector('img')&&!box.innerHTML.includes('href="javascript:'),'message markup cannot inject active HTML');
  box.querySelector('a').click();assert(requests.at(-1).action==='openLink'&&requests.at(-1).value.includes('a_(b)'),'nested URL reaches Android unchanged');box.remove();
  applyState({replace:true,ready:true,busy:true,loggedIn:true,status:'Codex работает…',order:['generated'],updates:[{id:'generated',kind:'image',text:'Изображение готово',complete:true,images:[{id:fixture}]}],outbox:[]});
  setTimeout(()=>{try{render();
   assert(document.querySelector('[data-image] img')!==null,'generated image renders in the conversation');document.querySelector('[data-image]').click();assert(requests.at(-1).action==='openImage','generated image opens through native bridge');
   $('prompt').value='Уточнение';$('prompt').dispatchEvent(new Event('input'));$('delivery-button').click();clickOption('Уточнить сейчас');$('send').click();assert(requests.some(r=>r.action==='send'&&r.mode==='steer'),'steering remains available with attachment controls');
   $('account-button').click();clickOption('Уведомления о задачах');assert(requests.at(-1).action==='notificationSettings','notification controls open Android settings');
   const report=document.createElement('pre');report.id='test-report';report.hidden=true;report.textContent=JSON.stringify({status:'PASS',checks},null,2);document.body.append(report);
   // Leave the image/composer visible in the screenshot.
   setAttachments({images:[{id:fixture,url:'media-fixture.png'}],loading:false});
   assert($('send').getBoundingClientRect().right<=360&&$('stop').getBoundingClientRect().right<=360,'composer controls fit a 360px phone');
   report.textContent=JSON.stringify({status:'PASS',checks,layout:Object.fromEntries(['app','attach','delivery-button','mic','stop','send','prompt'].map(id=>[id,$(id).getBoundingClientRect().toJSON()]))},null,2);
  }catch(e){throw e;}},100);
 }catch(e){const p=document.createElement('pre');p.id='test-failure';p.textContent=e.stack;document.body.append(p);}
},100);
