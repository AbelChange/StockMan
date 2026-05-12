package com.liaobusi.stockman

import android.util.Base64
import android.webkit.WebView

object WebViewJsonInterceptor {
    /**
     * JSON is sent as Base64 to avoid JS string escaping issues.
     * Requires a JavascriptInterface named `JsonBridge` with `onJsonBase64(url, b64)`.
     */
    val hookScript: String = """
        (function(){
          if(window.__SM_JSON_HOOK__)return;
          window.__SM_JSON_HOOK__=true;
          var B=window.JsonBridge;
          if(!B||!B.onJsonBase64)return;
          function absUrl(u){
            try{return new URL(u,document.baseURI).href;}catch(e){return u||'';}
          }
          function looksJson(t){
            if(!t||typeof t!=='string')return false;
            var s=t.trim();
            if(s.length<2)return false;
            var c0=s.charAt(0),c1=s.charAt(s.length-1);
            if(!((c0==='{'&&c1==='}')||(c0==='['&&c1===']')))return false;
            try{JSON.parse(s);return true;}catch(e){return false;}
          }
          function notify(url,text){
            if(!looksJson(text))return;
            try{
              var b=btoa(unescape(encodeURIComponent(text)));
              B.onJsonBase64(url,b);
            }catch(e){}
          }
          var XPO=XMLHttpRequest.prototype;
          var oOpen=XPO.open;
          var oSend=XPO.send;
          XPO.open=function(method,url){
            this.__sm_url=absUrl(url);
            return oOpen.apply(this,arguments);
          };
          XPO.send=function(body){
            this.addEventListener('load',function(){
              var u=this.__sm_url||'';
              var ct=(this.getResponseHeader('Content-Type')||'').toLowerCase();
              if(ct.indexOf('json')>=0||ct.indexOf('javascript')>=0){
                notify(u,this.responseText);
              }else if(this.responseText){
                notify(u,this.responseText);
              }
            });
            return oSend.apply(this,arguments);
          };
          if(window.fetch){
            var nf=window.fetch;
            window.fetch=function(input,init){
              var raw=typeof input==='string'?input:(input&&input.url);
              var u=absUrl(raw||'');
              return nf.apply(this,arguments).then(function(resp){
                try{
                  var ct=(resp.headers.get('content-type')||'').toLowerCase();
                  if(ct.indexOf('json')>=0){
                    return resp.clone().text().then(function(txt){
                      notify(u,txt);
                      return resp;
                    });
                  }
                }catch(e){}
                return resp;
              });
            };
          }
        })();
    """.trimIndent()

    fun inject(webView: WebView) {
        webView.evaluateJavascript(hookScript, null)
    }

    fun decodeBase64Json(b64: String): String? {
        if (b64.isEmpty()) return null
        return runCatching {
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }
}
