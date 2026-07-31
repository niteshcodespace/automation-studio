package com.automationstudio.api.execution.engine.playwright.runtime;

import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightRuntimeProperties;
import java.time.Duration;

interface PlaywrightSdk {

    SdkPlaywright create();

    interface SdkPlaywright extends AutoCloseable {
        boolean isDefaultChromiumAvailable();

        SdkBrowser launch(
                PlaywrightExecutionConfiguration configuration,
                PlaywrightRuntimeProperties properties);

        @Override
        void close();
    }

    interface SdkBrowser extends AutoCloseable {
        SdkBrowserContext createContext(PlaywrightExecutionConfiguration configuration);

        @Override
        void close();
    }

    interface SdkBrowserContext extends AutoCloseable {
        SdkPage createBlankPage();

        @Override
        void close();
    }

    interface SdkPage extends AutoCloseable {
        void navigate(String uri, Duration timeout);
        void click(String selector, Duration timeout);
        void fill(String selector, String value, Duration timeout);
        boolean isVisible(String selector, Duration timeout);
        String textContent(String selector, Duration timeout);
        String url();

        @Override
        void close();
    }
}
