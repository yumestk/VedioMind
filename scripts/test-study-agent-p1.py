#!/usr/bin/env python3
"""Run real Flyway + study authorization on disposable MySQL 8 databases; never print credentials."""
import os
import shutil
from pathlib import Path
import subprocess
import uuid
ROOT = Path(__file__).resolve().parents[1]
CONTAINER = os.environ.get('P1_MYSQL_CONTAINER', 'mysql-media')
def mysql(sql):
    return subprocess.run(['docker','exec','-i',CONTAINER,'sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names'],
        input=sql,text=True,capture_output=True,check=True)
def main():
    databases = ['vediomind_auth_test_'+uuid.uuid4().hex, 'vediomind_study_test_'+uuid.uuid4().hex, 'vediomind_study_test_'+uuid.uuid4().hex, 'vediomind_study_test_'+uuid.uuid4().hex, 'vediomind_study_test_'+uuid.uuid4().hex, 'vediomind_study_test_'+uuid.uuid4().hex]
    created=[]
    try:
        for database in databases:
            mysql('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4;'); created.append(database)
        credential=subprocess.run(['docker','exec',CONTAINER,'sh','-c','printf %s "$MYSQL_ROOT_PASSWORD"'],
            text=True,capture_output=True,check=True).stdout
        env={**os.environ,'AUTH_TEST_MYSQL_USER':'root','AUTH_TEST_MYSQL_PASSWORD':credential,
            'AUTH_TEST_MIGRATION_URL':'jdbc:mysql://127.0.0.1:3307/'+databases[0]+'?allowPublicKeyRetrieval=true&useSSL=false',
            'STUDY_TEST_MYSQL_URL':'jdbc:mysql://127.0.0.1:3307/'+databases[1]+'?allowPublicKeyRetrieval=true&useSSL=false',
            'STUDY_UPGRADE_MYSQL_URL':'jdbc:mysql://127.0.0.1:3307/'+databases[2]+'?allowPublicKeyRetrieval=true&useSSL=false',
            'STUDY_P2_UPGRADE_MYSQL_URL':'jdbc:mysql://127.0.0.1:3307/'+databases[3]+'?allowPublicKeyRetrieval=true&useSSL=false',
            'STUDY_EXECUTION_MYSQL_URL':'jdbc:mysql://127.0.0.1:3307/'+databases[4]+'?allowPublicKeyRetrieval=true&useSSL=false',
            'STUDY_P3_UPGRADE_MYSQL_URL':'jdbc:mysql://127.0.0.1:3307/'+databases[5]+'?allowPublicKeyRetrieval=true&useSSL=false'}
        # Maven removes deleted test classes, but leaves their old reports behind.
        shutil.rmtree(ROOT/'server/target/surefire-reports', ignore_errors=True)
        log=ROOT/'server/target/study-agent-p1-mysql-tests.log'
        with log.open('w') as output:
            result=subprocess.run(['./mvnw','-q','test'],cwd=ROOT/'server',env=env,stdout=output,stderr=subprocess.STDOUT)
        print('MySQL + full backend regression:', 'PASS' if result.returncode==0 else 'FAIL', '- log:',log)
        if result.returncode: raise SystemExit(result.returncode)
    finally:
        for database in reversed(created):
            assert database.startswith(('vediomind_auth_test_','vediomind_study_test_'))
            mysql('DROP DATABASE `'+database+'`;')
if __name__=='__main__': main()
