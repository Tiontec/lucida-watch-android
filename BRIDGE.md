# 澄影安卓壳 ↔ 网页接口（window.LucidaAndroid）
所有方法返回 "ok" 或 "err:原因"（info 返回 JSON）。
- info() → {platform,version,sdk,model,webview,dual:{ok,why?,concurrent?}}
- beginSave(id,name,mime) / chunk(id,index,base64) / endSave(id) / abortSave(id)：分块保存，写完壳调用 window.__lucidaSaveDone(id,{ok,name,where}|{ok:false,error})
- dualOpen(json{mode:"photo"|"video"})：打开原生双摄；关闭后壳调用 window.__lucidaDualDone({ok:true,items:[{kind,name,url,saved}]} | {ok:false,code:"unsupported"|"denied"|"error",error})。文件地址 https://lucida.local/__dual/<name>
- dualClean(json[names])：导入完后删除临时文件（空数组 = 全删）
壳 → 网页：window.__lucidaKb(cssPx)（键盘盖住网页容器底部的高度）、window.__lucidaBack()（返回键，返回 true 表示已处理）
