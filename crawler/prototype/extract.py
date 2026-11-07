import json, base64, sys, time, collections
from gm import call
N={'type':'INTEGER'}; S={'type':'STRING'}
cnt={'type':'ARRAY','items':{'type':'OBJECT','properties':{'전형':S,'인원':{'type':'INTEGER','nullable':True},'원문':S},'required':['전형','원문']}}
schema={'type':'OBJECT','properties':{
 '학교':S,'학년도':N,
 '모집인원':{'type':'ARRAY','items':{'type':'OBJECT','properties':{'계열':S,'단위':{'type':'STRING','enum':['학과','단과대학']},'모집단위':S,'인원':cnt},'required':['계열','단위','모집단위','인원']}},
 '소계':{'type':'ARRAY','items':{'type':'OBJECT','properties':{'계열':S,'인원':cnt},'required':['계열','인원']}},
 '총계':cnt,
 '일정':{'type':'ARRAY','items':{'type':'OBJECT','properties':{'항목':S,'시작':S,'종료':S,'비고':S},'required':['항목','시작']}}},
 'required':['학교','학년도','모집인원','총계','일정']}
PROMPT="""이 PDF는 대학 편입학 모집요강이다. 다음을 추출하라.
- 모집인원: 표의 모든 전형 열(일반편입, 학사편입, 정원외 특별전형 등 열 이름 그대로)을 빠짐없이. 입학정원 열은 제외. 원문에는 셀에 적힌 그대로('3', '1명 이내', '8명 내외', '약간명', '미선발', '-'). 인원은 확정 숫자일 때만 정수, '이내/내외/약간명'이면 null, '미선발'·'-'·빈칸이면 0. 셀이 여러 학과에 걸친 병합 셀(단과대 단위 인원)이면 학과에 나누지 말고 단위='단과대학' 행으로 따로 기록. 학과마다 모든 전형 열을 기록할 것.
- 소계/총계: 표에 적힌 계열별 소계와 총계를 그대로(직접 계산하지 말 것). 없으면 빈 배열.
- 일정: 원서접수, 필기시험, 면접, 합격자 발표, 등록 등. 날짜는 YYYY-MM-DD 또는 YYYY-MM-DD HH:MM.
문서에 없는 값은 지어내지 말 것."""
def tot(rows): 
    c=collections.Counter()
    for r in rows:
        for x in r['인원']:
            if x['인원'] is not None: c[x['전형']]+=x['인원']
    return c
model, path = sys.argv[1], sys.argv[2]
b64=base64.b64encode(open(path,'rb').read()).decode()
t=time.time()
res=call(f'models/{model}:generateContent',{'contents':[{'parts':[{'inline_data':{'mime_type':'application/pdf','data':b64}},{'text':PROMPT}]}],
  'generationConfig':{'responseMimeType':'application/json','responseSchema':schema,'temperature':0}})
d=json.loads(res['candidates'][0]['content']['parts'][0]['text'])
json.dump(d,open(path.replace('.pdf',f'.{model}.v2.json'),'w'),ensure_ascii=False,indent=1)
print(f"{d['학교']} {d['학년도']} | {time.time()-t:.0f}s | tokens {res.get('usageMetadata',{})}")
s=tot(d['모집인원']); T={x['전형']:x['인원'] for x in d['총계'] if x['인원'] is not None}
print('학과합',dict(s),'| 총계',T,'| 일치' if s==collections.Counter(T) else '| 불일치')
for g in d.get('소계',[]):
    gs=tot([r for r in d['모집인원'] if r['계열']==g['계열']]); gt={x['전형']:x['인원'] for x in g['인원'] if x['인원'] is not None}
    print(' 소계',g['계열'],dict(gs),gt,'OK' if gs==collections.Counter(gt) else 'X')
print('비숫자 인원',sum(1 for r in d['모집인원'] for x in r['인원'] if x['인원'] is None),'칸, 단과대 행',sum(r['단위']=='단과대학' for r in d['모집인원']))
print('일정',len(d['일정']),'건:',[(e['항목'],e['시작']) for e in d['일정']][:8])
