"""Opt-in local integration test. Creates clearly named test records in the demo database.
Run with Python 3 after starting the app: python scripts/verify-local.py
Set CAM_TEST_ADMIN_PASSWORD, CAM_TEST_FACULTY_PASSWORD, CAM_TEST_STUDENT_PASSWORD
if the documented local demo passwords were changed.
"""
import datetime,http.cookiejar,json,os,time,urllib.request,urllib.error
BASE='http://127.0.0.1:8080/api'
class Client:
 def __init__(self): self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def call(self,path,method='GET',body=None,status=200,csrf=True):
  headers={}
  if method!='GET' and csrf: headers['X-XSRF-TOKEN']=self.call('/auth/csrf')['token']
  if body is not None: headers['Content-Type']='application/json'
  request=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
  try:
   r=self.opener.open(request); code=r.status; raw=r.read(); content=r.headers.get('Content-Type','')
  except urllib.error.HTTPError as e: code=e.code;raw=e.read();content=e.headers.get('Content-Type','')
  assert code==status,(method,path,code,status,raw[:300])
  return json.loads(raw) if raw and 'application/json' in content else raw.decode()
 def login(self,role,password): return self.call('/auth/login','POST',{'email':role+'@college.test','password':password})
a,f,s=Client(),Client(),Client()
Client().call('/admin/users',status=401)
a.call('/auth/login','POST',{'email':'admin@college.test','password':'wrong'},status=403,csrf=False)
a.call('/auth/login','POST',{'email':'admin@college.test','password':'wrong'},status=401)
a.login('admin',os.getenv('CAM_TEST_ADMIN_PASSWORD','CampusAdmin-Change-123!'))
f.login('faculty',os.getenv('CAM_TEST_FACULTY_PASSWORD','FacultyDemo-2026!'))
s.login('student',os.getenv('CAM_TEST_STUDENT_PASSWORD','StudentDemo-2026!'))
s.call('/admin/users',status=403);f.call('/admin/users',status=403)
s.call('/reports/attendance',status=403)
stamp=str(int(time.time()))
u=a.call('/admin/users','POST',{'displayName':'Verification student '+stamp,'email':'verify'+stamp+'@college.test','role':'STUDENT','password':'Verification-Temp-123!'})
# Same June-May academic calendar the admin page suggests; the semester is the one containing today.
today=datetime.date.today(); y=today.year if today.month>=6 else today.year-1; first=6<=today.month<=11
setup={'departmentCode':'TEST','departmentName':'Verification','yearLabel':f'{y}-{y+1}','yearStart':f'{y}-06-01','yearEnd':f'{y+1}-05-31',
       'semester':'Semester 1' if first else 'Semester 2','semesterStart':f'{y}-06-01' if first else f'{y}-12-01','semesterEnd':f'{y}-11-30' if first else f'{y+1}-05-31',
       'courseCode':'T'+stamp,'courseName':'Verification course','sectionCode':'T'+stamp,'subjectCode':'SUB','subjectName':'Verification subject'}
day=today.isoformat()
c=a.call('/admin/academic/setup','POST',setup)
users=a.call('/admin/users');fid=next(x['id'] for x in users if x['email']=='faculty@college.test')
a.call('/admin/enrollments','POST',{'studentId':u['id'],'sectionId':c['sectionId']})
a.call('/admin/assignments','POST',{'userId':fid,'sectionId':c['sectionId'],'subjectId':c['subjectId']})
assignment=next(x for x in f.call('/faculty/classes') if x['sectionId']==c['sectionId'])
session=f.call('/faculty/classes/'+str(assignment['assignmentId'])+'/sessions','POST',{'sessionDate':day,'sessionLabel':'Verification '+stamp})
sid=session['id']; path='/faculty/sessions/'+str(sid)+'/attendance'
f.call(path,'PUT',{'expectedVersion':0,'records':[{'studentId':u['id'],'status':'PRESENT'}]})
f.call(path,'PUT',{'expectedVersion':0,'records':[{'studentId':u['id'],'status':'ABSENT'}]},status=409)
f.call(path,'PUT',{'expectedVersion':1,'records':[{'studentId':u['id'],'status':'LATE'}]})
rows=a.call('/reports/attendance?from='+day+'&to='+day+'&sectionId='+str(c['sectionId']))
assert len(rows)==1 and rows[0]['status']=='LATE'
assert 'LATE' in a.call('/reports/attendance.csv?sectionId='+str(c['sectionId']))
assert s.call('/student/attendance/summary')['total']==2
assert all(x.get('sessionLabel')!='Verification '+stamp for x in s.call('/student/attendance/history')['content'])
v=Client();v.call('/auth/login','POST',{'email':u['email'],'password':'Verification-Temp-123!'})
v.call('/student/attendance/summary',status=403)  # temporary password must be replaced first
v.call('/auth/password','POST',{'currentPassword':'Verification-Temp-123!','newPassword':'Verification-Local-123!'})
assert v.call('/student/attendance/summary')['late']==1
assert v.call('/student/attendance/summary')['percentage']==100
v.call('/faculty/sessions/'+str(sid),status=403)
a.call('/admin/manage/users/'+str(u['id']),'PUT',{'value':u['displayName'],'active':False})
v.call('/student/attendance/summary',status=401)
f.call('/faculty/classes/999999/roster',status=403)
a.call('/auth/logout','POST',{});a.call('/admin/users',status=401)
print('PASS: login, CSRF, roles, admin setup/users/enrollment/assignment, faculty save/update/conflict, temporary password, student isolation, date/class reports, CSV, disabled session, logout.')
print('Persistence check session ID:',sid)
