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
package org.gradle.api.internal.artifacts;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import org.gradle.api.Action;
import org.gradle.api.ActionConfiguration;
import org.gradle.api.attributes.AttributeCompatibilityRule;
import org.gradle.api.attributes.AttributeDisambiguationRule;
import org.gradle.api.attributes.AttributeMatchingStrategy;
import org.gradle.api.attributes.AttributesSchema;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.CompatibilityCheckDetails;
import org.gradle.api.attributes.HasAttributes;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.MultipleCandidatesDetails;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.CompileView;
import org.gradle.api.attributes.java.TargetJvmEnvironment;
import org.gradle.api.attributes.java.TargetJvmVersion;
import org.gradle.api.internal.ReusableAction;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.DescribableAttributesSchema;
import org.gradle.api.model.ObjectFactory;

import javax.inject.Inject;
import java.util.Optional;
import java.util.Set;

public abstract class JavaEcosystemSupport {

    @SuppressWarnings("deprecation")
    private static final String DEPRECATED_JAVA_API_JARS = Usage.JAVA_API_JARS;
    @SuppressWarnings("deprecation")
    private static final String DEPRECATED_JAVA_RUNTIME_JARS = Usage.JAVA_RUNTIME_JARS;

    public static void configureSchema(AttributesSchema attributesSchema, final ObjectFactory objectFactory) {
        configureUsage(attributesSchema, objectFactory);
        configureLibraryElements(attributesSchema, objectFactory);
        configureView(attributesSchema, objectFactory);
        configureBundling(attributesSchema);
        configureTargetPlatform(attributesSchema);
        configureTargetEnvironment(attributesSchema);
        configureConsumerDescriptors((DescribableAttributesSchema) attributesSchema);
        attributesSchema.attributeDisambiguationPrecedence(
                Category.CATEGORY_ATTRIBUTE,
                Usage.USAGE_ATTRIBUTE,
                CompileView.VIEW_ATTRIBUTE,
                TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                Bundling.BUNDLING_ATTRIBUTE,
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE
        );
    }

    private static void configureConsumerDescriptors(DescribableAttributesSchema attributesSchema) {
        attributesSchema.addConsumerDescriber(new JavaEcosystemAttributesDescriber());
    }

    public static void configureDefaultTargetPlatform(HasAttributes configuration, int majorVersion) {
        AttributeContainerInternal attributes = (AttributeContainerInternal) configuration.getAttributes();
        // If nobody said anything about this variant's target platform, use whatever the convention says
        if (!attributes.contains(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE)) {
            attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, majorVersion);
        }
    }

    private static void configureTargetPlatform(AttributesSchema attributesSchema) {
        AttributeMatchingStrategy<Integer> targetPlatformSchema = attributesSchema.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE);
        targetPlatformSchema.getCompatibilityRules().ordered(Ordering.natural());
        targetPlatformSchema.getDisambiguationRules().pickLast(Ordering.natural());
    }

    private static void configureTargetEnvironment(AttributesSchema attributesSchema) {
        AttributeMatchingStrategy<TargetJvmEnvironment> targetEnvironmentSchema = attributesSchema.attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE);
        targetEnvironmentSchema.getCompatibilityRules().add(TargetJvmEnvironmentCompatibilityRules.class);
        targetEnvironmentSchema.getDisambiguationRules().add(TargetJvmEnvironmentDisambiguationRules.class);
    }

    private static void configureBundling(AttributesSchema attributesSchema) {
        AttributeMatchingStrategy<Bundling> bundlingSchema = attributesSchema.attribute(Bundling.BUNDLING_ATTRIBUTE);
        bundlingSchema.getCompatibilityRules().add(BundlingCompatibilityRules.class);
        bundlingSchema.getDisambiguationRules().add(BundlingDisambiguationRules.class);
    }

    private static void configureUsage(AttributesSchema attributesSchema, final ObjectFactory objectFactory) {
        AttributeMatchingStrategy<Usage> usageSchema = attributesSchema.attribute(Usage.USAGE_ATTRIBUTE);
        usageSchema.getCompatibilityRules().add(UsageCompatibilityRules.class);
        usageSchema.getDisambiguationRules().add(UsageDisambiguationRules.class, new Action<ActionConfiguration>() {
            @Override
            public void execute(ActionConfiguration actionConfiguration) {
                actionConfiguration.params(objectFactory.named(Usage.class, Usage.JAVA_API));
                actionConfiguration.params(objectFactory.named(Usage.class, DEPRECATED_JAVA_API_JARS));
                actionConfiguration.params(objectFactory.named(Usage.class, Usage.JAVA_RUNTIME));
                actionConfiguration.params(objectFactory.named(Usage.class, DEPRECATED_JAVA_RUNTIME_JARS));
            }
        });
    }

    private static void configureLibraryElements(AttributesSchema attributesSchema, final ObjectFactory objectFactory) {
        AttributeMatchingStrategy<LibraryElements> libraryElementsSchema = attributesSchema.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE);
        libraryElementsSchema.getCompatibilityRules().add(LibraryElementsCompatibilityRules.class);
        libraryElementsSchema.getDisambiguationRules().add(LibraryElementsDisambiguationRules.class, actionConfiguration -> {
            actionConfiguration.params(objectFactory.named(LibraryElements.class, LibraryElements.JAR));
        });
    }

    private static void configureView(AttributesSchema attributesSchema, final ObjectFactory objectFactory) {
        AttributeMatchingStrategy<CompileView> viewSchema = attributesSchema.attribute(CompileView.VIEW_ATTRIBUTE);
        viewSchema.getCompatibilityRules().add(CompileViewCompatibilityRules.class);
        viewSchema.getDisambiguationRules().add(CompileViewDisambiguationRules.class, actionConfiguration -> {
            actionConfiguration.params(objectFactory.named(CompileView.class, CompileView.JAVA_API));
            actionConfiguration.params(objectFactory.named(CompileView.class, CompileView.JAVA_INTERNAL));
        });
    }

    @VisibleForTesting
    static class CompileViewDisambiguationRules implements AttributeDisambiguationRule<CompileView>, ReusableAction {

        final CompileView javaApi;
        final CompileView javaInternal;

        @Inject
        public CompileViewDisambiguationRules(
                CompileView javaApi,
                CompileView javaInternal) {
            this.javaApi = javaApi;
            this.javaInternal = javaInternal;
        }

        @Override
        public void execute(MultipleCandidatesDetails<CompileView> details) {
            Set<CompileView> candidateValues = details.getCandidateValues();
            CompileView consumerValue = details.getConsumerValue();
            if (consumerValue == null) {
                if (candidateValues.contains(javaApi)) {
                    // Use the api when nothing has been requested
                    details.closestMatch(javaApi);
                }
            } else if (candidateValues.contains(consumerValue)) {
                // Use what they requested, if available
                details.closestMatch(consumerValue);
            }
        }
    }

    @VisibleForTesting
    static class CompileViewCompatibilityRules implements AttributeCompatibilityRule<CompileView>, ReusableAction {

        @Override
        public void execute(CompatibilityCheckDetails<CompileView> details) {
            CompileView consumerValue = details.getConsumerValue();
            CompileView producerValue = details.getProducerValue();
            if (consumerValue == null) {
                // consumer didn't express any preferences, everything fits
                details.compatible();
                return;
            }
            // The API view is a subset of the internal view, so the internal view can fulfill
            // a request for the API.
            if (CompileView.JAVA_API.equals(consumerValue.getName()) && CompileView.JAVA_INTERNAL.equals(producerValue.getName())) {
                details.compatible();
            }
        }
    }

    @VisibleForTesting
    static class UsageDisambiguationRules implements AttributeDisambiguationRule<Usage>, ReusableAction {
        final Usage javaApi;
        final Usage javaRuntime;
        final Usage javaApiJars;
        final Usage javaRuntimeJars;

        final ImmutableSet<Usage> apiVariants;
        final ImmutableSet<Usage> runtimeVariants;

        @Inject
        UsageDisambiguationRules(Usage javaApi,
                                 Usage javaApiJars,
                                 Usage javaRuntime,
                                 Usage javaRuntimeJars) {
            this.javaApi = javaApi;
            this.javaApiJars = javaApiJars;
            this.apiVariants = ImmutableSet.of(javaApi, javaApiJars);
            this.javaRuntime = javaRuntime;
            this.javaRuntimeJars = javaRuntimeJars;
            this.runtimeVariants = ImmutableSet.of(javaRuntime, javaRuntimeJars);
        }

        @Override
        public void execute(MultipleCandidatesDetails<Usage> details) {
            Set<Usage> candidateValues = details.getCandidateValues();
            Usage consumerValue = details.getConsumerValue();
            if (consumerValue == null) {
                if (candidateValues.contains(javaRuntimeJars)) {
                    // Use the Jars when nothing has been requested
                    details.closestMatch(javaRuntimeJars);
                } else if (candidateValues.contains(javaRuntime)) {
                    // Use the runtime when nothing has been requested
                    details.closestMatch(javaRuntime);
                }
            } else {
                if (javaRuntime.equals(consumerValue)) {
                    // we're asking for a runtime variant, prefer -jars first
                    if (candidateValues.contains(javaRuntimeJars)) {
                        details.closestMatch(javaRuntimeJars);
                    } else if (candidateValues.contains(javaRuntime)) {
                        details.closestMatch(javaRuntime);
                    }
                } else if (javaApi.equals(consumerValue)) {
                    // we're asking for an API variant, prefer -jars first for runtime
                    if (candidateValues.contains(javaApiJars)) {
                        details.closestMatch(javaApiJars);
                    } else if (candidateValues.contains(javaApi)) {
                        details.closestMatch(javaApi);
                    } else if (candidateValues.contains(javaRuntimeJars)) {
                        details.closestMatch(javaRuntimeJars);
                    } else if (candidateValues.contains(javaRuntime)) {
                        details.closestMatch(javaRuntime);
                    }
                } else if (candidateValues.contains(consumerValue)) {
                    details.closestMatch(consumerValue);
                }
            }
        }
    }

    @VisibleForTesting
    static class UsageCompatibilityRules implements AttributeCompatibilityRule<Usage>, ReusableAction {
        private static final Set<String> COMPATIBLE_WITH_JAVA_API = ImmutableSet.of(
                DEPRECATED_JAVA_API_JARS,
                DEPRECATED_JAVA_RUNTIME_JARS,
                Usage.JAVA_RUNTIME
        );
        @Override
        public void execute(CompatibilityCheckDetails<Usage> details) {
            Usage consumerValue = details.getConsumerValue();
            Usage producerValue = details.getProducerValue();
            if (consumerValue == null) {
                // consumer didn't express any preferences, everything fits
                details.compatible();
                return;
            }
            if (consumerValue.getName().equals(Usage.JAVA_API)) {
                if (COMPATIBLE_WITH_JAVA_API.contains(producerValue.getName())) {
                    details.compatible();
                }
                return;
            }
            if (consumerValue.getName().equals(Usage.JAVA_RUNTIME) && producerValue.getName().equals(DEPRECATED_JAVA_RUNTIME_JARS)) {
                details.compatible();
                return;
            }
        }
    }

    @VisibleForTesting
    static class LibraryElementsDisambiguationRules implements AttributeDisambiguationRule<LibraryElements>, ReusableAction {
        final LibraryElements jar;

        @Inject
        LibraryElementsDisambiguationRules(LibraryElements jar) {
            this.jar = jar;
        }

        @Override
        public void execute(MultipleCandidatesDetails<LibraryElements> details) {
            Set<LibraryElements> candidateValues = details.getCandidateValues();
            LibraryElements consumerValue = details.getConsumerValue();
            if (consumerValue == null) {
                if (candidateValues.contains(jar)) {
                    // Use the jar when nothing has been requested
                    details.closestMatch(jar);
                }
            } else if (candidateValues.contains(consumerValue)) {
                // Use what they requested, if available
                details.closestMatch(consumerValue);
            }
        }
    }

    @VisibleForTesting
    static class LibraryElementsCompatibilityRules implements AttributeCompatibilityRule<LibraryElements>, ReusableAction {

        @Override
        public void execute(CompatibilityCheckDetails<LibraryElements> details) {
            LibraryElements consumerValue = details.getConsumerValue();
            LibraryElements producerValue = details.getProducerValue();
            if (consumerValue == null) {
                // consumer didn't express any preferences, everything fits
                details.compatible();
                return;
            }
            String consumerValueName = consumerValue.getName();
            String producerValueName = producerValue.getName();
            if (LibraryElements.CLASSES.equals(consumerValueName) || LibraryElements.RESOURCES.equals(consumerValueName) || LibraryElements.CLASSES_AND_RESOURCES.equals(consumerValueName)) {
                // JAR is compatible with classes or resources
                if (LibraryElements.JAR.equals(producerValueName)) {
                    details.compatible();
                    return;
                }
            }
        }
    }

    private static class TargetJvmEnvironmentCompatibilityRules implements AttributeCompatibilityRule<TargetJvmEnvironment>, ReusableAction {

        // public constructor to make reflective initialization happy.
        public TargetJvmEnvironmentCompatibilityRules() {}

        @Override
        public void execute(CompatibilityCheckDetails<TargetJvmEnvironment> details) {
            details.compatible();
        }
    }

    private static class TargetJvmEnvironmentDisambiguationRules implements AttributeDisambiguationRule<TargetJvmEnvironment>, ReusableAction {

        // public constructor to make reflective initialization happy.
        public TargetJvmEnvironmentDisambiguationRules() {}

        @Override
        public void execute(MultipleCandidatesDetails<TargetJvmEnvironment> details) {
            TargetJvmEnvironment consumerValue = details.getConsumerValue();
            if (consumerValue != null && details.getCandidateValues().contains(consumerValue)) {
                details.closestMatch(consumerValue); // exact match
            } else {
                Optional<TargetJvmEnvironment> standardJvm = details.getCandidateValues().stream().filter(c -> TargetJvmEnvironment.STANDARD_JVM.equals(c.getName())).findFirst();
                standardJvm.ifPresent(details::closestMatch);
            }
        }
    }

    @VisibleForTesting
    static class BundlingCompatibilityRules implements AttributeCompatibilityRule<Bundling>, ReusableAction {
        private static final Set<String> COMPATIBLE_WITH_EXTERNAL = ImmutableSet.of(
                // if we ask for "external" dependencies, it's still fine to bring a fat jar if nothing else is available
                Bundling.EMBEDDED,
                Bundling.SHADOWED
        );

        @Override
        public void execute(CompatibilityCheckDetails<Bundling> details) {
            Bundling consumerValue = details.getConsumerValue();
            Bundling producerValue = details.getProducerValue();
            if (consumerValue == null) {
                // consumer didn't express any preference, everything fits
                details.compatible();
                return;
            }
            String consumerValueName = consumerValue.getName();
            String producerValueName = producerValue.getName();
            if (Bundling.EXTERNAL.equals(consumerValueName)) {
                if (COMPATIBLE_WITH_EXTERNAL.contains(producerValueName)) {
                    details.compatible();
                }
            } else if (Bundling.EMBEDDED.equals(consumerValueName)) {
                // asking for a fat jar. If everything available is a shadow jar, that's fine
                if (Bundling.SHADOWED.equals(producerValueName)) {
                    details.compatible();
                }
            }
        }
    }

    @VisibleForTesting
    static class BundlingDisambiguationRules implements AttributeDisambiguationRule<Bundling>, ReusableAction {

        @Override
        public void execute(MultipleCandidatesDetails<Bundling> details) {
            Bundling consumerValue = details.getConsumerValue();
            Set<Bundling> candidateValues = details.getCandidateValues();
            if (candidateValues.contains(consumerValue)) {
                details.closestMatch(consumerValue);
                return;
            }
            if (consumerValue == null) {
                Bundling embedded = null;
                for (Bundling candidateValue : candidateValues) {
                    if (Bundling.EXTERNAL.equals(candidateValue.getName())) {
                        details.closestMatch(candidateValue);
                        return;
                    } else if (Bundling.EMBEDDED.equals(candidateValue.getName())) {
                        embedded = candidateValue;
                    }
                }
                if (embedded != null) {
                    details.closestMatch(embedded);
                }
            } else {
                String consumerValueName = consumerValue.getName();
                if (Bundling.EXTERNAL.equals(consumerValueName)) {
                    for (Bundling candidateValue : candidateValues) {
                        if (Bundling.EMBEDDED.equals(candidateValue.getName())) {
                            details.closestMatch(candidateValue);
                            return;
                        }
                    }
                }
            }
        }
    }

}
