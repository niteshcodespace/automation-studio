package com.automationstudio.engine.selenium.worker;

final class WorkerProtocolException extends Exception {
    private final String code;
    WorkerProtocolException(String code) { super("Selenium worker protocol failed"); this.code = code; }
    String code() { return code; }
}
