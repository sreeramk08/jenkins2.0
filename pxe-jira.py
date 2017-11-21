#!/usr/bin/python

import sys
import argparse
from jira import JIRA
import commands
import os
import traceback

'''
Usage: ./jira/pxe-jira.py create_release -n ' + RELEASE_NAME + ' -d ' + RELEASE_DATE + ' -t ' + TKTS_RESOLVED_TXT + JIRA_AUTH

./jira/pxe-jira.py create_release -n ' + RELEASE_NAME + ' -d ' + RELEASE_DATE + ' -t ' + TKTS_RESOLVED_TXT + JIRA_AUTH

JIRA_AUTH = ' -u ' + JIRA_USER + ' -p ' + JIRA_PSWD

'''

class Jira(object):
    """Python-Jira API class"""
    def __init__(self, args):
        """Init"""
        self.jira_server = 'https://company.jira.net'
        self.jira = JIRA('%s' % self.jira_server, basic_auth=(args.jira_user,args.jira_pswd))

        # Arguments
        self.release_name = args.release_name
        self.release_date = args.release_date
        self.project = args.project
        self.status = args.status
        self.fix_version = args.fix_version
        self.tkts_resolved = args.tkts_resolved
        self.verbose = args.verbose

        self.issues = []

    def search_issues(self):
        """Return issues searched with JQL"""
        search_str = 'project = %s AND status = %s AND fixVersion = %s order by lastViewed DESC' % \
                     (self.project, self.status, self.fix_version)

        return self.jira.search_issues(search_str)

    def is_version_exist(self):
        """Check if version exist"""
        # New version is in the last element of the list
        version = self.jira.project_versions(self.project)[-1]

        if str(version) == self.release_name:
            return True
        else:
            return False

    def create_version(self):
        """Create Jira release version"""
        descrp = []

        # Adding tickets summary to release description
        for tkt in self.issues:
            descrp.append((tkt.fields.summary).encode('UTF8'))

        descrp = '. '.join(descrp)

        self.jira.create_version(self.release_name,\
                                 self.project,\
                                 description=descrp,\
                                 releaseDate=self.release_date,\
                                 startDate=None,\
                                 archived=False,\
                                 released=True)

    def tickets_resolved_log(self):
        """Create a file with resolved tickets summary"""
        version = self.jira.project_versions(self.project)[-1]
        release_url = '%s/projects/%s/versions/%s/tab/release-report-all-issues/' % (self.jira_server, self.project, str(version.id))
        
        hdr_str = "======================================================\n" \
                  "The new Fixes or Features added in this release are:\n" \
                  "\n" \
                  "%s\n\n" % release_url 

        tail_str = "======================================================"

        with open(self.tkts_resolved, "a") as tkts_file:
            tkts_file.write(hdr_str)
            for tkt in self.issues:
                tkts_file.write("%s: %s \n" % (tkt.key, tkt.fields.summary))
            tkts_file.write(tail_str)

    def update_issue_fix_version(self):
        """ Update fixVersion of tickets"""
        fixVersion = []

        fixVersion.append({'name': self.release_name})
        for tkt in self.issues:
            tkt.update(fields={'fixVersions': fixVersion})

    def create_release(self):
        """ Workflow to create release:
            1. Search tickets for release
            2. Create Jira release version
            3. Update fixVersion of tickets
            4. Parse tickets resolved to file for release announcment
        """
        self.issues = self.search_issues()

        version_exist = self.is_version_exist()
        if version_exist == False:
            self.create_version()

        self.update_issue_fix_version()

        self.tickets_resolved_log()

    def command(self, args):
        """Commands for Jira release"""
        if 'create_release' in args.command:
            self.create_release()

        elif 'close_release' in args.command:
            self.close_release()

########################################################################################

def main_execute(args):
    """Execute command"""
    jira_obj = Jira(args)

    jira_obj.command(args)

def parse_cl_args():
    """Return args"""
    parse_description = "Python-Jira API script"

    parse_epilog = "create_release - Creates a release version on Jira board and tags resolved tickets with release name.\n"

    parser = argparse.ArgumentParser(description=parse_description, epilog=parse_epilog)

    parser.add_argument("-v", "--verbose", action="store_true", \
                         default=False, \
                         help="Increase output verbosity")

    # Commands
    parser.add_argument('command', \
                        choices=['create_release','close_release'], \
                        help="Jira release commands")

    # Arguments
    parser.add_argument("-n", "--releasename", dest="release_name", \
                        default="TEST", \
                        help="Release name")

    parser.add_argument("-d", "--releasedate", dest="release_date", \
                        default=None, \
                        help="Release date")

    parser.add_argument("--project", dest="project", \
                        default="PXE", \
                        help="Project name")

    parser.add_argument("--status", dest="status", \
                        default="Done", \
                        help="Status of issues")

    parser.add_argument("--fixversion", dest="fix_version", \
                        default="EMPTY", \
                        help="Fix version of issues")

    parser.add_argument("-t", "--tickets", dest="tkts_resolved", \
                        default="/tmp/Tickets_Resolved.txt", \
                        help="Tickets resolved file")

    parser.add_argument("-u", "--user", dest="jira_user", \
                        help="Jira username")

    parser.add_argument("-p", "--password", dest="jira_pswd", \
                        help="Jira password")

    args = parser.parse_args()

    return args

if  __name__ == "__main__":
    args = parse_cl_args()
    main_execute(args)
