/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.test.spring;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.camel.impl.DefaultDebugger;
import org.apache.camel.impl.InterceptSendToMockEndpointStrategy;
import org.apache.camel.management.JmxSystemPropertyKeys;
import org.apache.camel.spi.Breakpoint;
import org.apache.camel.spi.Debugger;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.camel.test.ExcludingPackageScanClassResolver;
import org.apache.camel.test.spring.CamelSpringTestHelper.DoToSpringCamelContextsStrategy;
import org.apache.camel.util.CastUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.support.AbstractContextLoader;
import org.springframework.test.context.support.AbstractGenericContextLoader;
import org.springframework.test.context.support.GenericXmlContextLoader;
import org.springframework.util.StringUtils;

/**
 * Replacement for the default {@link GenericXmlContextLoader} that provides hooks for
 * processing some class level Camel related test annotations.
 */
public class CamelSpringTestContextLoader extends AbstractContextLoader {
    
    private static final Logger LOG = LoggerFactory.getLogger(CamelSpringTestContextLoader.class);
    
    /**
     *  Modeled after the Spring implementation in {@link AbstractGenericContextLoader},
     *  this method creates and refreshes the application context while providing for
     *  processing of additional Camel specific post-refresh actions.  We do not provide the
     *  pre-post hooks for customization seen in {@link AbstractGenericContextLoader} because
     *  they probably are unnecessary for 90+% of users.
     *  <p/>
     *  For some functionality, we cannot use {@link TestExecutionListener} because we need
     *  to both produce the desired outcome during application context loading, and also cleanup
     *  after ourselves even if the test class never executes.  Thus the listeners, which
     *  only run if the application context is successfully initialized are insufficient to
     *  provide the behavior described above.
     */
    @Override
    public ApplicationContext loadContext(MergedContextConfiguration mergedConfig) throws Exception {
        
        Class<?> testClass = getTestClass();
        
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("Loading ApplicationContext for merged context configuration [%s].",
                mergedConfig));
        }
        
        try {            
            GenericApplicationContext context = createContext(testClass);
            context.getEnvironment().setActiveProfiles(mergedConfig.getActiveProfiles());
            loadBeanDefinitions(context, mergedConfig);
            return loadContext(context, testClass);
        } finally {
            cleanup(testClass);
        }
    }
    
    /**
     *  Modeled after the Spring implementation in {@link AbstractGenericContextLoader},
     *  this method creates and refreshes the application context while providing for
     *  processing of additional Camel specific post-refresh actions.  We do not provide the
     *  pre-post hooks for customization seen in {@link AbstractGenericContextLoader} because
     *  they probably are unnecessary for 90+% of users.
     *  <p/>
     *  For some functionality, we cannot use {@link TestExecutionListener} because we need
     *  to both produce the desired outcome during application context loading, and also cleanup
     *  after ourselves even if the test class never executes.  Thus the listeners, which
     *  only run if the application context is successfully initialized are insufficient to
     *  provide the behavior described above.
     */
    @Override
    public ApplicationContext loadContext(String... locations) throws Exception {
        
        Class<?> testClass = getTestClass();
        
        if (LOG.isDebugEnabled()) {
            LOG.debug("Loading ApplicationContext for locations ["
                    + StringUtils.arrayToCommaDelimitedString(locations) + "].");
        }
        
        try {
            GenericApplicationContext context = createContext(testClass);
            loadBeanDefinitions(context, locations);
            return loadContext(context, testClass);
        } finally {
            cleanup(testClass);
        }
    }

    /**
     * Returns &quot;<code>-context.xml</code>&quot;.
     */
    @Override
    public String getResourceSuffix() {
        return "-context.xml";
    }
    
    /**
     * Performs the bulk of the Spring application context loading/customization.
     *
     * @param context the partially configured context.  The context should have the bean definitions loaded, but nothing else.
     * @param testClass the test class being executed
     *
     * @return the initialized (refreshed) Spring application context
     *
     * @throws Exception if there is an error during initialization/customization
     */
    protected ApplicationContext loadContext(GenericApplicationContext context, Class<?> testClass)
        throws Exception {
            
        AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
        
        // Pre CamelContext(s) instantiation setup
        handleDisableJmx(context, testClass);

        // Temporarily disable CamelContext start while the contexts are instantiated.
        SpringCamelContext.setNoStart(true);
        context.refresh();
        context.registerShutdownHook();
        // Turn CamelContext startup back on since the context's have now been instantiated.
        SpringCamelContext.setNoStart(false);
        
        // Post CamelContext(s) instantiation but pre CamelContext(s) start setup
        handleProvidesBreakpoint(context, testClass);
        handleShutdownTimeout(context, testClass);
        handleMockEndpoints(context, testClass);
        handleMockEndpointsAndSkip(context, testClass);
        handleLazyLoadTypeConverters(context, testClass);
        
        // CamelContext(s) startup
        handleCamelContextStartup(context, testClass);
        
        return context;
    }
    
    /**
     * Cleanup/restore global state to defaults / pre-test values after the test setup
     * is complete. 
     * 
     * @param testClass the test class being executed
     */
    protected void cleanup(Class<?> testClass) {
        SpringCamelContext.setNoStart(false);
        
        if (testClass.isAnnotationPresent(DisableJmx.class)) {
            if (CamelSpringTestHelper.getOriginalJmxDisabled() == null) {
                System.clearProperty(JmxSystemPropertyKeys.DISABLED);
            } else {
                System.setProperty(JmxSystemPropertyKeys.DISABLED,
                    CamelSpringTestHelper.getOriginalJmxDisabled());
            }
        }
    }
    
    protected void loadBeanDefinitions(GenericApplicationContext context, MergedContextConfiguration mergedConfig) {
        (new XmlBeanDefinitionReader(context)).loadBeanDefinitions(mergedConfig.getLocations());
    }
    
    protected void loadBeanDefinitions(GenericApplicationContext context, String... locations) {
        (new XmlBeanDefinitionReader(context)).loadBeanDefinitions(locations);
    }
    
    /**
     * Returns all methods defined in {@code clazz} and its superclasses/interfaces.
     */
    protected Collection<Method> getAllMethods(Class<?> clazz)  {
        
        Set<Method> methods = new HashSet<Method>();
        Class<?> currentClass = clazz;
        
        while (currentClass != null) {
            methods.addAll(Arrays.asList(clazz.getMethods()));
            currentClass = currentClass.getSuperclass(); 
        }
                
        return methods;
    }
    
    /**
     * Creates and starts the Spring context while optionally starting any loaded Camel contexts.
     *
     * @param testClass the test class that is being executed
     *
     * @return the loaded Spring context
     */
    protected GenericApplicationContext createContext(Class<?> testClass) {

        GenericApplicationContext routeExcludingContext = null;
        
        if (testClass.isAnnotationPresent(ExcludeRoutes.class)) {
            Class<?>[] excludedClasses = testClass.getAnnotation(
                    ExcludeRoutes.class).value();
            
            if (excludedClasses.length > 0) {
                
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Setting up package scanning excluded classes as ExcludeRoutes "
                            + "annotation was found.  Excluding ["
                            + StringUtils.arrayToCommaDelimitedString(excludedClasses) + "].");
                }
                
                routeExcludingContext = new GenericApplicationContext();
                routeExcludingContext.registerBeanDefinition(
                        "excludingResolver", new RootBeanDefinition(
                                ExcludingPackageScanClassResolver.class));
                routeExcludingContext.refresh();
                
                ExcludingPackageScanClassResolver excludingResolver = routeExcludingContext.getBean("excludingResolver", ExcludingPackageScanClassResolver.class);
                List<Class<?>> excluded = CastUtils.cast(Arrays.asList(excludedClasses));
                excludingResolver.setExcludedClasses(new HashSet<Class<?>>(excluded));
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Not enabling package scanning excluded classes as ExcludeRoutes "
                            + "annotation was found but no classes were excluded.");
                }
            }
        }
        
        GenericApplicationContext context;
        
        if (routeExcludingContext != null) {
            context = new GenericApplicationContext(routeExcludingContext);
        } else {
            context = new GenericApplicationContext();
        }
        
        return context;
    }
    
    /**
     * Handles disabling of JMX on Camel contexts based on {@link DisableJmx}.
     *
     * @param context the initialized Spring context
     * @param testClass the test class being executed
     */
    protected void handleDisableJmx(GenericApplicationContext context, Class<?> testClass) {
        CamelSpringTestHelper.setOriginalJmxDisabledValue(
                System.getProperty(JmxSystemPropertyKeys.DISABLED));
        
        if (testClass.isAnnotationPresent(DisableJmx.class)) {
            if (testClass.getAnnotation(DisableJmx.class).value()) {
                LOG.info("Disabling Camel JMX globally as DisableJmx annotation was found "
                        + "and disableJmx is set to true.");
                
                System.setProperty(JmxSystemPropertyKeys.DISABLED, "true");
                
            } else {
                LOG.info("Enabling Camel JMX as DisableJmx annotation was found "
                        + "and disableJmx is set to false.");
                
                System.clearProperty(JmxSystemPropertyKeys.DISABLED);
            }
        } else {
            LOG.info("Disabling Camel JMX globally for tests by default.  Use the DisableJMX annotation to "
                    + "override the default setting.");
            
            System.setProperty(JmxSystemPropertyKeys.DISABLED, "true");
        }
    }
    
    /**
     * Handles the processing of the {@link ProvidesBreakpoint} annotation on a test class.  Exists here
     * as it is needed in 
     *
     * @param context the initialized Spring context containing the Camel context(s) to insert breakpoints into 
     * @param testClass the test class being processed
     * @param log the logger to use
     * @param statics if static methods or instance methods should be processed
     *
     * @throws Exception if there is an error processing the class
     */
    protected void handleProvidesBreakpoint(GenericApplicationContext context, Class<?> testClass) throws Exception {
            
        Collection<Method> methods = getAllMethods(testClass);
        final List<Breakpoint> breakpoints = new LinkedList<Breakpoint>();
        
        for (Method method : methods) {
            
            if (AnnotationUtils.findAnnotation(method, ProvidesBreakpoint.class) != null) {
                Class<?>[] argTypes = method.getParameterTypes();
                if (argTypes.length != 0) {
                    throw new IllegalArgumentException("Method [" + method.getName()
                           + "] is annotated with ProvidesBreakpoint but is not a no-argument method.");
                } else if (!Breakpoint.class.isAssignableFrom(method.getReturnType())) {
                    throw new IllegalArgumentException("Method [" + method.getName()
                           + "] is annotated with ProvidesBreakpoint but does not return a Breakpoint.");
                } else if (!Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalArgumentException("Method [" + method.getName()
                           + "] is annotated with ProvidesBreakpoint but is not static.");
                } else if (!Modifier.isPublic(method.getModifiers())) {
                    throw new IllegalArgumentException("Method [" + method.getName()
                           + "] is annotated with ProvidesBreakpoint but is not public.");
                }
                
                try {
                    breakpoints.add((Breakpoint) method.invoke(null, new Object[] {}));
                } catch (Exception e) {
                    throw new RuntimeException("Method [" + method.getName()
                           + "] threw exception during evaluation.", e);
                }
            }
        }
        
        if (breakpoints.size() != 0) {
        
            CamelSpringTestHelper.doToSpringCamelContexts(context, new DoToSpringCamelContextsStrategy() {
                
                @Override
                public void execute(String contextName, SpringCamelContext camelContext)
                    throws Exception {
                    
                    Debugger debugger = camelContext.getDebugger();
                    if (debugger == null) {
                        debugger = new DefaultDebugger();
                        camelContext.setDebugger(debugger);
                    }
                    
                    for (Breakpoint breakpoint : breakpoints) {
                        LOG.info(
                                 "Adding Breakpoint [{}] to CamelContext with name [{}].",
                                 breakpoint, contextName);
                        
                        debugger.addBreakpoint(breakpoint);
                    }
                }
            });
        }
    }
    
    
    /**
     * Handles updating shutdown timeouts on Camel contexts based on {@link ShutdownTimeout}.
     *
     * @param context the initialized Spring context
     * @param testClass the test class being executed
     */
    protected void handleShutdownTimeout(GenericApplicationContext context, Class<?> testClass) throws Exception {
        
        final int shutdownTimeout;
        final TimeUnit shutdownTimeUnit;
        if (testClass.isAnnotationPresent(ShutdownTimeout.class)) {
            shutdownTimeout = testClass.getAnnotation(ShutdownTimeout.class).value();
            shutdownTimeUnit = testClass.getAnnotation(ShutdownTimeout.class).timeUnit();
        } else {
            shutdownTimeout = 10;
            shutdownTimeUnit = TimeUnit.SECONDS;
        }
        
        CamelSpringTestHelper.doToSpringCamelContexts(context, new DoToSpringCamelContextsStrategy() {
            
            @Override
            public void execute(String contextName, SpringCamelContext camelContext)
                throws Exception {
                
                LOG.info(
                        "Setting shutdown timeout to [{} {}] on CamelContext with name [{}].",
                        new Object[] {shutdownTimeout, shutdownTimeUnit, contextName});
                camelContext.getShutdownStrategy().setTimeout(shutdownTimeout);
                camelContext.getShutdownStrategy().setTimeUnit(shutdownTimeUnit);
            }
        });
    }
    
    /**
     * Handles auto-intercepting of endpoints with mocks based on {@link MockEndpoints}.
     *
     * @param context the initialized Spring context
     * @param testClass the test class being executed
     */
    protected void handleMockEndpoints(GenericApplicationContext context, Class<?> testClass) throws Exception {
        if (testClass.isAnnotationPresent(MockEndpoints.class)) {
            
            final String mockEndpoints = testClass.getAnnotation(
                    MockEndpoints.class).value();
            CamelSpringTestHelper.doToSpringCamelContexts(context, new DoToSpringCamelContextsStrategy() {
                
                @Override
                public void execute(String contextName, SpringCamelContext camelContext)
                    throws Exception {
                    
                    LOG.info("Enabling auto mocking of endpoints matching pattern [{}] on "
                            + "CamelContext with name [{}].", mockEndpoints, contextName);
                    camelContext.addRegisterEndpointCallback(
                            new InterceptSendToMockEndpointStrategy(mockEndpoints));
                }
            });
        }
    }
    
    /**
     * Handles auto-intercepting of endpoints with mocks based on {@link MockEndpoints} and skipping the
     * original endpoint.
     *
     * @param context the initialized Spring context
     * @param testClass the test class being executed
     */
    protected void handleMockEndpointsAndSkip(GenericApplicationContext context, Class<?> testClass) throws Exception {
        if (testClass.isAnnotationPresent(MockEndpointsAndSkip.class)) {
            
            final String mockEndpoints = testClass.getAnnotation(
                    MockEndpointsAndSkip.class).value();
            CamelSpringTestHelper.doToSpringCamelContexts(context, new DoToSpringCamelContextsStrategy() {
                
                @Override
                public void execute(String contextName, SpringCamelContext camelContext)
                    throws Exception {
                    
                    LOG.info("Enabling auto mocking and skipping of endpoints matching pattern [{}] on "
                            + "CamelContext with name [{}].", mockEndpoints, contextName);
                    camelContext.addRegisterEndpointCallback(
                            new InterceptSendToMockEndpointStrategy(mockEndpoints, true));
                }
            });
        }
    }
    
    @SuppressWarnings("deprecation")
    protected void handleLazyLoadTypeConverters(GenericApplicationContext context, Class<?> testClass) throws Exception {
        final boolean lazy;
        
        if (testClass.isAnnotationPresent(LazyLoadTypeConverters.class)) {
            lazy = testClass.getAnnotation(LazyLoadTypeConverters.class).value();
        } else {
            lazy = true;
        }
         
        if (lazy) {
            CamelSpringTestHelper.doToSpringCamelContexts(context, new DoToSpringCamelContextsStrategy() {
                
                @Override
                public void execute(String contextName, SpringCamelContext camelContext)
                    throws Exception {
                    
                    LOG.info("Enabling lazy loading of type converters on "
                            + "CamelContext with name [{}].", contextName);
                    camelContext.setLazyLoadTypeConverters(lazy);
                }
            });
        }
    }
    
    /**
     * Handles starting of Camel contexts based on {@link UseAdviceWith} and other state in the JVM.
     *
     * @param context the initialized Spring context
     * @param testClass the test class being executed
     */
    protected void handleCamelContextStartup(GenericApplicationContext context, Class<?> testClass) throws Exception {
        boolean skip = "true".equalsIgnoreCase(System.getProperty("skipStartingCamelContext"));
        if (skip) {
            LOG.info("Skipping starting CamelContext(s) as system property " 
                    + "skipStartingCamelContext is set to be true.");
        } else if (testClass.isAnnotationPresent(UseAdviceWith.class)) {
            if (testClass.getAnnotation(UseAdviceWith.class).value()) {
                LOG.info("Skipping starting CamelContext(s) as UseAdviceWith annotation was found "
                        + "and isUseAdviceWith is set to true.");
                skip = true;
            } else {
                LOG.info("Starting CamelContext(s) as UseAdviceWith annotation was found, but "
                        + "isUseAdviceWith is set to false.");
                skip = false;
            }
        }
        
        if (!skip) {
            CamelSpringTestHelper.doToSpringCamelContexts(context, new DoToSpringCamelContextsStrategy() {
                
                @Override
                public void execute(String contextName,
                        SpringCamelContext camelContext) throws Exception {
                    LOG.info("Starting CamelContext with name [{}].", contextName);
                    camelContext.start();
                }
            });
        }
    }
    
    /**
     * Returns the class under test in order to enable inspection of annotations while the
     * Spring context is being created.
     * 
     * @return the test class that is being executed
     * @see CamelSpringTestHelper
     */
    protected Class<?> getTestClass() {
        return CamelSpringTestHelper.getTestClass();
    }
}
