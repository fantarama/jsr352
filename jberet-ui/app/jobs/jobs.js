'use strict';

var angular = require('angular');
var utils = require('../common/utils');

angular.module('jberetUI.jobs',
    ['ui.router', 'ui.bootstrap', 'ngAnimate', 'ngTouch', 'ui.grid', 'ui.grid.selection', 'ui.grid.exporter'])

    .config(['$stateProvider', function ($stateProvider) {
        $stateProvider.state('jobs', {
            url: '/jobs',
            templateUrl: 'jobs/jobs.html',
            controller: 'JobsCtrl'
        });
    }])

    .directive('bindContentTo', function () {
        return {
            restrict: 'A',

            link: function (scope, element, attrs) {
                element.bind('change', function(event) {
                    var file = event.target.files[0];
                    var reader = new FileReader();
                    reader.onload = function(){
                        scope.$apply(function () {
                            scope[attrs.bindContentTo] = reader.result;
                        });
                    };
                    reader.readAsText(file);
                });
            }
        };
    })

    .controller('JobsCtrl', ['$scope', '$log', 'batchRestService', 'localRecentJobsService',
        function ($scope, $log, batchRestService, localRecentJobsService) {
        var jobInstancesLinkCell =
            '<div class="ngCellText" ng-class="col.colIndex()"><a ui-sref="jobinstances({jobName: row.entity.jobName})">{{COL_FIELD}}</a></div>';

        var jobExecutionsLinkCell =
            '<div class="ngCellText" ng-class="col.colIndex()"><a ng-class="grid.appScope.getLinkActiveClass(COL_FIELD)" ui-sref="jobexecutions({jobName: row.entity.jobName, running: true})">{{COL_FIELD}}</a></div>';

        $scope.alerts = [];
        $scope.gridOptions = new utils.DefaultGridOptions(8, true, 'jobs.csv',
            [
                {name: 'jobName'},
                {name: 'numberOfJobInstances', cellTemplate: jobInstancesLinkCell},
                {name: 'numberOfRunningJobExecutions', type: 'number', cellTemplate: jobExecutionsLinkCell}
            ]
        );

        $scope.startJob = function () {
            $scope.alerts.length = 0; //clear alerts
            $scope.stateTransitionParams = null;
            var jobParams = utils.parseJobParameters($scope.jobParameters);
            batchRestService.startJob($scope.jobName, jobParams).then(function (responseData) {
                $scope.jobExecutionEntity = responseData.data;
                $scope.stateTransitionParams = {
                    jobExecutionId: $scope.jobExecutionEntity.executionId,
                    jobExecutionEntity: $scope.jobExecutionEntity,
                    jobName: $scope.jobName,
                    jobInstanceId: $scope.jobExecutionEntity.jobInstanceId,
                    jobExecutionId1: $scope.jobExecutionEntity.executionId
                };
                $scope.alerts.push({
                    type: 'success',
                    msg: 'Started job: ' + $scope.jobName +
                    ((!jobParams) ? '.' : ', with parameters: ' + utils.formatAsKeyValuePairs(jobParams) + '.')
                });

                getRecentJobs();
                resetFields();
            }, function (responseData) {
                $log.debug(responseData);
                $scope.alerts.push({
                    type: 'danger',
                    msg: 'Failed to start job: ' + $scope.jobName + '.'
                });
                resetFields();
            });
        };

        $scope.closeAlert = function (index) {
            $scope.alerts.splice(index, 1);
        };

        $scope.getLinkActiveClass = function(value) {
            return value > 0 ? '' : 'not-active';
        };

        function getRecentJobs() {
            batchRestService.getJobs().then(function (responseData) {
                //combine recent jobs list from the REST call with recent jobs saved in local storage
                var recentJobsFromServer = responseData.data;
                var recentJobsLocal = localRecentJobsService.getLocalRecentJobs();
                if(recentJobsLocal) {
                    for (var i = 0; i < recentJobsLocal.length; i++) {
                        var localJobAlreadyIncluded = false;
                        for(var j = 0; j < recentJobsFromServer.length; j++) {
                            if(recentJobsLocal[i] === recentJobsFromServer[j].jobName) {
                                localJobAlreadyIncluded = true;
                                break;
                            }
                        }
                        if(!localJobAlreadyIncluded) {
                            recentJobsFromServer.push({
                                jobName: recentJobsLocal[i],
                                numberOfJobInstances: 'View'
                            });
                        }
                    }
                }
                $scope.gridOptions.data = recentJobsFromServer;
            }, function (responseData) {
                $log.debug(responseData);
            });
        }

        function resetFields() {
            $scope.jobName = '';
            $scope.jobParameters = '';
            document.getElementById('jobParametersLoad').value = null;
        }

        getRecentJobs();
    }]);
