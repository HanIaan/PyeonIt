import json, urllib.request, sys
import os
key=os.environ.get('GEMINI_API_KEY') or [l.split('=',1)[1].strip().strip("'\"") for l in open(os.environ.get('ENV_FILE','.env')) if l.startswith('GEMINI_API_KEY=')][0]
def call(path, body=None):
    r=urllib.request.Request('https://generativelanguage.googleapis.com/v1beta/'+path, data=json.dumps(body).encode() if body else None,
        headers={'x-goog-api-key':key,'Content-Type':'application/json'})
    return json.load(urllib.request.urlopen(r, timeout=580))
if __name__=='__main__':
    for m in call('models?pageSize=200')['models']:
        if 'generateContent' in m.get('supportedGenerationMethods',[]): print(m['name'])
