#!/usr/bin/env python
# Licensed to Cloudera, Inc. under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  Cloudera, Inc. licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import logging
import os

from django.core.management.base import BaseCommand
import spark.conf


LOG = logging.getLogger(__name__)


class Command(BaseCommand):
    """
    Starts livy server.
    """

    args = '<process(default)|yarn>'
    help = 'start livy server with process or yarn workers'

    def handle(self, *args, **kwargs):
        if not args:
          session_kind = 'process'
        else:
          session_kind = args[0].lower()

        env = os.environ.copy()

        args = [
          os.path.join(
            os.path.dirname(__file__),
            "..",
            "..",
            "..",
            "..",
            "java",
            "bin",
            "livy-server"),
          session_kind,
        ]

        LOG.info("Executing %r (%r) (%r)" % (bin, args, env))

        # Use exec, so that this takes only one process.
        os.execve(args[0], args, env)
