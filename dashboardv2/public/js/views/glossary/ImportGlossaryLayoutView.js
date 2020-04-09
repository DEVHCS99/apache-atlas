/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

define([
    "require",
    "backbone",
    "hbs!tmpl/glossary/ImportGlossaryLayoutView_tmpl",
    "modules/Modal",
    'utils/CommonViewFunction',
    "utils/Utils",
    "utils/UrlLinks",
    "dropzone"
], function(require, Backbone, ImportGlossaryLayoutViewTmpl, Modal, CommonViewFunction, Utils, UrlLinks, dropzone) {
    var ImportGlossaryLayoutView = Backbone.Marionette.LayoutView.extend(
        /** @lends ImportGlossaryLayoutView */
        {
            _viewName: "ImportGlossaryLayoutView",

            template: ImportGlossaryLayoutViewTmpl,

            templateHelpers: function() {
                return {
                    importUrl: UrlLinks.glossaryImportUrl()
                };
            },

            /** Layout sub regions */
            regions: {},

            /** ui selector cache */
            ui: {},
            /** ui events hash */
            events: function() {
                var events = {};
                return events;
            },
            /**
             * intialize a new ImportGlossaryLayoutView Layout
             * @constructs
             */
            initialize: function(options) {
                _.extend(this, _.pick(options, "callback"));
                var that = this;
                this.modal = new Modal({
                    title: "Import Glossary",
                    content: this,
                    cancelText: "Cancel",
                    okText: "upload",
                    allowCancel: true,
                    okCloses: false,
                    mainClass: "dropzone-modal"
                }).open();
                this.modal.$el.find("button.ok").attr("disabled", true);
                this.modal.on("ok", function(e) {
                    that.dropzone.processQueue();
                });
                this.modal.on("closeModal", function() {
                    that.modal.trigger("cancel");
                });
            },
            bindEvents: function() {},
            onRender: function() {
                var that = this;
                this.dropzone = null;
                var updateButton = function(files) {
                    var buttonEl = that.modal.$el.find("button.ok");
                    if (files.length === 0) {
                        buttonEl.attr("disabled", true);
                    } else {
                        buttonEl.attr("disabled", false);
                    }
                }
                var headers = {};
                headers[CommonViewFunction.restCsrfCustomHeader] = '""';
                this.$("#importGlossary").dropzone({
                    url: UrlLinks.glossaryImportUrl(),
                    clickable: true,
                    acceptedFiles: ".csv,.xls,.xlsx",
                    autoProcessQueue: false,
                    maxFiles: 1,
                    addRemoveLinks: true,
                    init: function() {
                        that.dropzone = this;
                        this.on('addedfile', function(file) {
                            updateButton(this.files);
                        })
                        this.on('removedfile', function(file) {
                            updateButton(this.files);
                        })
                    },
                    maxfilesexceeded: function(file) {
                        this.removeAllFiles();
                        this.addFile(file);
                    },
                    success: function(file, response) {
                        that.modal.trigger("cancel");
                        Utils.notifySuccess({
                            content: "File: " + file.name + " added successfully"
                        });
                        that.callback();
                    },
                    error: function(file, response, responseObj) {
                        Utils.defaultErrorHandler(null, responseObj, { defaultErrorMessage: (response.errorMessage) || response });
                        that.modal.$el.find("button.ok").attr("disabled", false);
                    },
                    dictDefaultMessage: "Drop files here or click to upload.",
                    headers: headers
                });
            }
        }
    );
    return ImportGlossaryLayoutView;
});