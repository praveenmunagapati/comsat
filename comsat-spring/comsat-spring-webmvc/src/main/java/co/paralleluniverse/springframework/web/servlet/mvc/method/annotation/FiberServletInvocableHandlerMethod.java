/*
 * COMSAT
 * Copyright (c) 2013-2014, Parallel Universe Software Co. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
/*
 * Based on org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod
 * in Spring Framework Web MVC.
 * Copyright the original author Rossen Stoyanchev.
 * Released under the ASF 2.0 license.
 */
package co.paralleluniverse.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import org.springframework.http.HttpStatus;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite;
import org.springframework.web.method.support.InvocableHandlerMethod;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.View;
import org.springframework.web.util.NestedServletException;

import co.paralleluniverse.springframework.web.method.support.FiberInvocableHandlerMethod;

// TODO subclass instead of this copy&paste horror when https://jira.spring.io/browse/SPR-12484 is released

/**
 * Extends {@link InvocableHandlerMethod} with the ability to handle return
 * values through a registered {@link HandlerMethodReturnValueHandler} and
 * also supports setting the response status based on a method-level
 * {@code @ResponseStatus} annotation.
 *
 * <p>A {@code null} return value (including void) may be interpreted as the
 * end of request processing in combination with a {@code @ResponseStatus}
 * annotation, a not-modified check condition
 * (see {@link ServletWebRequest#checkNotModified(long)}), or
 * a method argument that provides access to the response stream.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 */
public class FiberServletInvocableHandlerMethod extends FiberInvocableHandlerMethod {

    private HttpStatus responseStatus;

    private String responseReason;

    private HandlerMethodReturnValueHandlerComposite returnValueHandlers;

    /**
     * Creates an instance from the given handler and method.
     */
    public FiberServletInvocableHandlerMethod(Object handler, Method method) {
        super(handler, method);
        initResponseStatus();
    }

    /**
     * Create an instance from a {@code HandlerMethod}.
     */
    public FiberServletInvocableHandlerMethod(HandlerMethod handlerMethod) {
        super(handlerMethod);
        initResponseStatus();
    }

    private void initResponseStatus() {
        ResponseStatus annot = getMethodAnnotation(ResponseStatus.class);
        if (annot != null) {
            this.responseStatus = annot.value();
            this.responseReason = annot.reason();
        }
    }

    /**
     * Register {@link HandlerMethodReturnValueHandler} instances to use to
     * handle return values.
     */
    public void setHandlerMethodReturnValueHandlers(HandlerMethodReturnValueHandlerComposite returnValueHandlers) {
        this.returnValueHandlers = returnValueHandlers;
    }

    /**
     * Invokes the method and handles the return value through a registered
     * {@link HandlerMethodReturnValueHandler}.
     *
     * @param webRequest the current request
     * @param mavContainer the ModelAndViewContainer for this request
     * @param providedArgs "given" arguments matched by type, not resolved
     */
    public final void invokeAndHandle(ServletWebRequest webRequest,
        ModelAndViewContainer mavContainer, Object... providedArgs) throws Exception {

        Object returnValue = invokeForRequest(webRequest, mavContainer, providedArgs);

        setResponseStatus(webRequest);

        if (returnValue == null) {
            if (isRequestNotModified(webRequest) || hasResponseStatus() || mavContainer.isRequestHandled()) {
                mavContainer.setRequestHandled(true);
                return;
            }
        } else if (StringUtils.hasText(this.responseReason)) {
            mavContainer.setRequestHandled(true);
            return;
        }

        mavContainer.setRequestHandled(false);

        try {
            this.returnValueHandlers.handleReturnValue(returnValue, getReturnValueType(returnValue), mavContainer, webRequest);
        } catch (Exception ex) {
            if (logger.isTraceEnabled()) {
                logger.trace(getReturnValueHandlingErrorMessage("Error handling return value", returnValue), ex);
            }
            throw ex;
        }
    }

    /**
     * Set the response status according to the {@link ResponseStatus} annotation.
     */
    private void setResponseStatus(ServletWebRequest webRequest) throws IOException {
        if (this.responseStatus == null) {
            return;
        }

        if (StringUtils.hasText(this.responseReason)) {
            webRequest.getResponse().sendError(this.responseStatus.value(), this.responseReason);
        }
        else {
            webRequest.getResponse().setStatus(this.responseStatus.value());
        }

        // to be picked up by the RedirectView
        webRequest.getRequest().setAttribute(View.RESPONSE_STATUS_ATTRIBUTE, this.responseStatus);
    }

    /**
     * Does the given request qualify as "not modified"?
     * @see ServletWebRequest#checkNotModified(long)
     * @see ServletWebRequest#checkNotModified(String)
     */
    private boolean isRequestNotModified(ServletWebRequest webRequest) {
        return webRequest.isNotModified();
    }

    /**
     * Does this method have the response status instruction?
     */
    private boolean hasResponseStatus() {
        return responseStatus != null;
    }

    private String getReturnValueHandlingErrorMessage(String message, Object returnValue) {
        StringBuilder sb = new StringBuilder(message);
        if (returnValue != null) {
            sb.append(" [type=").append(returnValue.getClass().getName()).append("] ");
        }
        sb.append("[value=").append(returnValue).append("]");
        return getDetailedErrorMessage(sb.toString());
    }

    /**
     * Create a ServletInvocableHandlerMethod that will return the given value from an
     * async operation instead of invoking the controller method again. The async result
     * value is then either processed as if the controller method returned it or an
     * exception is raised if the async result value itself is an Exception.
     */
    FiberServletInvocableHandlerMethod wrapConcurrentResult(final Object result) {
        return new CallableHandlerMethod(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                if (result instanceof Exception) {
                        throw (Exception) result;
                }
                else if (result instanceof Throwable) {
                        throw new NestedServletException("Async processing failed", (Throwable) result);
                }
                return result;
            }
        });
    }


    /**
     * A sub-class of {@link HandlerMethod} that invokes the given {@link Callable}
     * instead of the target controller method. This is useful for resuming processing
     * with the result of an async operation. The goal is to process the value returned
     * from the Callable as if it was returned by the target controller method, i.e.
     * taking into consideration both method and type-level controller annotations (e.g.
     * {@code @ResponseBody}, {@code @ResponseStatus}, etc).
     */
    private class CallableHandlerMethod extends FiberServletInvocableHandlerMethod {
        public CallableHandlerMethod(Callable<?> callable) {
            super(callable, ClassUtils.getMethod(callable.getClass(), "call"));
            this.setHandlerMethodReturnValueHandlers(FiberServletInvocableHandlerMethod.this.returnValueHandlers);
        }

        // Don't spawn fibers when the same URL is dispatchde (and so the same method is called)
        // after completing the (async on fibers) main invocation
        @Override
        protected Object doInvoke(Object... args) throws Exception {
            return threadBlockingInvoke(args);
        }

        /**
         * Bridge to type-level annotations of the target controller method.
         */
        @Override
        public Class<?> getBeanType() {
            return FiberServletInvocableHandlerMethod.this.getBeanType();
        }

        /**
         * Bridge to method-level annotations of the target controller method.
         */
        @Override
        public <A extends Annotation> A getMethodAnnotation(Class<A> annotationType) {
            return FiberServletInvocableHandlerMethod.this.getMethodAnnotation(annotationType);
        }
    }
}
