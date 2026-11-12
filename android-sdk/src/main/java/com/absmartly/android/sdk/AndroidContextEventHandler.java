package com.absmartly.android.sdk;

import androidx.annotation.NonNull;

import com.absmartly.sdk.Context;
import com.absmartly.sdk.ContextEventHandler;
import com.absmartly.sdk.json.Attribute;
import com.absmartly.sdk.json.PublishEvent;

import java.util.Map;

import java8.util.concurrent.CompletableFuture;

/**
 * Reports the Android SDK in the sdk_name and sdk_version system attributes. core-api fills them
 * with its own name and version; an attribute the user set under either name is left unchanged.
 */
final class AndroidContextEventHandler implements ContextEventHandler {
    static final String SDK_NAME_ATTRIBUTE = "sdk_name";
    static final String SDK_VERSION_ATTRIBUTE = "sdk_version";

    private final ContextEventHandler delegate;
    private final String sdkName;
    private final String sdkVersion;

    AndroidContextEventHandler(@NonNull ContextEventHandler delegate, @NonNull String sdkName,
            @NonNull String sdkVersion) {
        this.delegate = delegate;
        this.sdkName = sdkName;
        this.sdkVersion = sdkVersion;
    }

    @Override
    public CompletableFuture<Void> publish(@NonNull Context context, @NonNull PublishEvent event) {
        if (event.attributes != null) {
            // core-api leaves out a system attribute the user set themselves, so an entry with one of
            // these names that is not among the user's attributes is the core-api system attribute.
            final Map<String, Object> userAttributes = context.getAttributes();
            final Attribute[] attributes = event.attributes.clone();
            for (int i = 0; i < attributes.length; ++i) {
                final Attribute attribute = attributes[i];
                if (userAttributes.containsKey(attribute.name)) {
                    continue;
                }
                if (SDK_NAME_ATTRIBUTE.equals(attribute.name)) {
                    attributes[i] = new Attribute(attribute.name, sdkName, attribute.setAt);
                } else if (SDK_VERSION_ATTRIBUTE.equals(attribute.name)) {
                    attributes[i] = new Attribute(attribute.name, sdkVersion, attribute.setAt);
                }
            }
            event.attributes = attributes;
        }
        return delegate.publish(context, event);
    }
}
