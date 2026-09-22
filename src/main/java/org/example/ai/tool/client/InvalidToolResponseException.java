package org.example.ai.tool.client;

/** 远端契约不完整或不匹配；不得将其转换成零值或空数据。 */
public class InvalidToolResponseException extends IllegalStateException {
    public InvalidToolResponseException(String message) {
        super(message);
    }
}
