package org.jetbrains.teamcity.oidc.auth;

import jetbrains.buildServer.controllers.interceptors.RequestInterceptors;
import jetbrains.buildServer.controllers.interceptors.SkippableInterceptor;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class InterceptorExample extends SkippableInterceptor {
    public InterceptorExample(RequestInterceptors interceptors) {
        interceptors.addInterceptor(this);
    }

    @Override
    protected boolean preHandleInternal(HttpServletRequest httpServletRequest,
                                        HttpServletResponse httpServletResponse,
                                        Object handler) throws Exception {
        return false;
    }
}
