/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.plugin.devel.tasks


import org.gradle.test.fixtures.file.TestFile

import static org.gradle.internal.reflect.validation.Severity.ERROR
import static org.gradle.internal.reflect.validation.Severity.WARNING

class RuntimePluginValidationIntegrationTest extends AbstractPluginValidationIntegrationSpec {

    @Override
    def setup() {
        buildFile << """
            tasks.register("run", MyTask)
        """
    }

    final String iterableSymbol = '.$0'

    @Override
    String getNameSymbolFor(String name) {
        ".$name\$0"
    }

    @Override
    String getKeySymbolFor(String name) {
        ".$name"
    }

    @Override
    void assertValidationSucceeds() {
        succeeds "run"
        result.assertTaskNotSkipped(":run")
    }

    void assertValidationFailsWith(boolean expectDeprecationsForErrors, List<DocumentedProblem> messages) {
        def expectedDeprecations = messages
            .findAll { problem -> expectDeprecationsForErrors || problem.severity == WARNING }
        def expectedFailures = messages
            .findAll { problem -> problem.severity == ERROR }

        expectedDeprecations.forEach { warning ->
            String expectedMessage = removeTypeForProperties(warning.message) + " " +
                "This behaviour has been deprecated and is scheduled to be removed in Gradle 7.0. " +
                "Execution optimizations are disabled to ensure correctness. " +
                "See https://docs.gradle.org/current/userguide/${warning.id}.html#${warning.section} for more details."
            executer.expectDocumentedDeprecationWarning(expectedMessage)
        }
        if (expectedFailures) {
            fails "run"
        } else {
            succeeds "run"
        }
        result.assertTaskNotSkipped(":run")

        switch (expectedFailures.size()) {
            case 0:
                break
            case 1:
                failure.assertHasDescription("A problem was found with the configuration of task ':run' (type 'MyTask').")
                break
            default:
                failure.assertHasDescription("Some problems were found with the configuration of task ':run' (type 'MyTask').")
                break
        }
        expectedFailures.forEach { error ->
            failureDescriptionContains(error.message)
        }
    }

    static String removeTypeForProperties(String message) {
        message.replaceAll(/Type '.*?': property/, "Property")
    }

    @Override
    TestFile source(String path) {
        return file("buildSrc/$path")
    }

    def "supports recursive types"() {
        groovyTaskSource << """
            import org.gradle.api.*
            import org.gradle.api.tasks.*

            class MyTask extends DefaultTask {
                @Nested
                Tree tree = new Tree(
                        left: new Tree([:]),
                        right: new Tree([:])
                    )

                public static class Tree {
                    @Optional @Nested
                    Tree left

                    @Optional @Nested
                    Tree right

                    String nonAnnotated
                }

                @TaskAction void execute() {}
            }
        """

        expect:
        assertValidationFailsWith(
            "Property 'tree.nonAnnotated' is not annotated with an input or output annotation.": WARNING,
            "Property 'tree.left.nonAnnotated' is not annotated with an input or output annotation.": WARNING,
            "Property 'tree.right.nonAnnotated' is not annotated with an input or output annotation.": WARNING,
        )
    }
}
