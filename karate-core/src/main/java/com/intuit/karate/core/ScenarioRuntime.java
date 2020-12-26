/*
 * The MIT License
 *
 * Copyright 2020 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate.core;

import com.intuit.karate.RuntimeHook;
import com.intuit.karate.ScenarioActions;
import com.intuit.karate.FileUtils;
import com.intuit.karate.Json;
import com.intuit.karate.JsonUtils;
import com.intuit.karate.KarateException;
import com.intuit.karate.LogAppender;
import com.intuit.karate.Logger;
import com.intuit.karate.http.ResourceType;
import com.intuit.karate.shell.FileLogAppender;
import com.intuit.karate.shell.StringLogAppender;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author pthomas3
 */
public class ScenarioRuntime implements Runnable {

    public final Logger logger;
    public final FeatureRuntime featureRuntime;
    public final ScenarioRuntime background;
    public final ScenarioCall caller;
    public final Scenario scenario;
    public final Tags tags;
    public final ScenarioActions actions;
    public final ScenarioResult result;
    public final ScenarioEngine engine;
    public final boolean reportDisabled;
    public final Map<String, Object> magicVariables;
    public final boolean dryRun;

    public ScenarioRuntime(FeatureRuntime featureRuntime, Scenario scenario) {
        this(featureRuntime, scenario, null);
    }

    public ScenarioRuntime(FeatureRuntime featureRuntime, Scenario scenario, ScenarioRuntime background) {
        logger = new Logger();
        this.featureRuntime = featureRuntime;
        this.caller = featureRuntime.caller;
        if (caller.isNone()) {
            engine = new ScenarioEngine(new Config(), this, new HashMap(), logger);
        } else if (caller.isSharedScope()) {
            Config config = caller.parentRuntime.engine.getConfig();
            Map<String, Variable> vars = caller.parentRuntime.engine.vars;
            engine = new ScenarioEngine(config, this, vars, logger);
        } else { // new, but clone and copy data
            Config config = new Config(caller.parentRuntime.engine.getConfig());
            Map<String, Variable> vars = caller.parentRuntime.engine.copyVariables(false);
            engine = new ScenarioEngine(config, this, vars, logger);
        }
        actions = new ScenarioActions(engine);
        this.scenario = scenario;
        magicVariables = initMagicVariables(); // depends on scenario
        this.background = background; // used only to check which steps remain
        result = new ScenarioResult(scenario);
        if (background != null) {
            result.addStepResults(background.result.getStepResults());
        }
        tags = scenario.getTagsEffective();
        if (featureRuntime.perfHook != null) {
            logAppender = LogAppender.NO_OP;
            reportDisabled = true;
        } else {
            reportDisabled = tags.valuesFor("report").isAnyOf("false");
        }
        if (!featureRuntime.caller.isNone()) {
            resultAppender = new StringLogAppender(true);
        }
        dryRun = featureRuntime.suite.dryRun;
    }

    public boolean isFailed() {
        return error != null || result.isFailed();
    }

    public Step getCurrentStep() {
        return currentStep;
    }

    public boolean isStopped() {
        return stopped;
    }

    public String getEmbedFileName(ResourceType resourceType) {
        String extension = resourceType == null ? null : resourceType.getExtension();
        return scenario.getUniqueId() + "_" + System.currentTimeMillis() + (extension == null ? "" : "." + extension);
    }

    public Embed saveToFileAndCreateEmbed(byte[] bytes, ResourceType resourceType) {
        File file = new File(featureRuntime.suite.reportDir + File.separator + getEmbedFileName(resourceType));
        FileUtils.writeToFile(file, bytes);
        return new Embed(file, resourceType);
    }

    public Embed embed(byte[] bytes, ResourceType resourceType) {
        if (embeds == null) {
            embeds = new ArrayList();
        }
        Embed embed = saveToFileAndCreateEmbed(bytes, resourceType);
        embeds.add(embed);
        return embed;
    }

    public Embed embedVideo(File file) {
        StepResult stepResult = result.addFakeStepResult("[video]", null);
        Embed embed = saveToFileAndCreateEmbed(FileUtils.toBytes(file), ResourceType.MP4);
        stepResult.addEmbed(embed);
        return embed;
    }

    private List<FeatureResult> callResults;

    public void addCallResult(FeatureResult fr) {
        if (callResults == null) {
            callResults = new ArrayList();
        }
        callResults.add(fr);
    }

    private LogAppender logAppender;
    private LogAppender resultAppender;

    public LogAppender getLogAppender() {
        return logAppender;
    }

    private static final ThreadLocal<LogAppender> LOG_APPENDER = new ThreadLocal<LogAppender>() {
        @Override
        protected LogAppender initialValue() {
            String fileName = FileUtils.getBuildDir() + File.separator + "karate-"
                    + Thread.currentThread().getName() + ".log";
            return new FileLogAppender(new File(fileName));
        }
    };

    private static final ThreadLocal<LogAppender> RESULT_APPENDER = new ThreadLocal<LogAppender>() {
        @Override
        protected LogAppender initialValue() {
            String fileName = FileUtils.getBuildDir() + File.separator + "karate-"
                    + Thread.currentThread().getName() + ".txt";
            return new FileLogAppender(new File(fileName));
        }
    };

    private List<Step> steps;
    private List<Embed> embeds;
    private StepResult currentStepResult;
    private Step currentStep;
    private Throwable error;
    private boolean configFailed;
    private boolean stopped;
    private boolean aborted;
    private int stepIndex;

    public void stepBack() {
        stopped = false;
        stepIndex -= 2;
        if (stepIndex < 0) {
            stepIndex = 0;
        }
    }

    public void stepReset() {
        stopped = false;
        stepIndex--;
        if (stepIndex < 0) { // maybe not required
            stepIndex = 0;
        }
    }

    public void stepProceed() {
        stopped = false;
    }

    private int nextStepIndex() {
        return stepIndex++;
    }

    public Result evalAsStep(String expression) {
        Step evalStep = new Step(scenario, -1);
        try {
            evalStep.parseAndUpdateFrom(expression);
        } catch (Exception e) {
            return Result.failed(0, e, evalStep);
        }
        return StepRuntime.execute(evalStep, actions);
    }

    public boolean hotReload() {
        boolean success = false;
        Feature feature = scenario.getFeature();
        feature = Feature.read(feature.getResource());
        for (Step oldStep : steps) {
            Step newStep = feature.findStepByLine(oldStep.getLine());
            if (newStep == null) {
                continue;
            }
            String oldText = oldStep.getText();
            String newText = newStep.getText();
            if (!oldText.equals(newText)) {
                try {
                    oldStep.parseAndUpdateFrom(newStep.getText());
                    logger.info("hot reloaded line: {} - {}", newStep.getLine(), newStep.getText());
                    success = true;
                } catch (Exception e) {
                    logger.warn("failed to hot reload step: {}", e.getMessage());
                }
            }
        }
        return success;
    }

    public Map<String, Object> getScenarioInfo() {
        Map<String, Object> info = new HashMap(5);
        File featureFile = featureRuntime.feature.getResource().getFile();
        if (featureFile != null) {
            info.put("featureDir", featureFile.getParent());
            info.put("featureFileName", featureFile.getName());
        }
        info.put("scenarioName", scenario.getName());
        info.put("scenarioDescription", scenario.getDescription());
        String errorMessage = error == null ? null : error.getMessage();
        info.put("errorMessage", errorMessage);
        return info;
    }

    protected void logError(String message) {
        if (currentStep != null) {
            message = currentStep.getDebugInfo()
                    + "\n" + currentStep.toString()
                    + "\n" + message;
        }
        logger.error("{}", message);
    }

    private Map<String, Object> initMagicVariables() {
        Map<String, Object> map = new HashMap();
        if (caller.isNone()) { // if feature called via java api
            if (caller.arg != null && caller.arg.isMap()) {
                map.putAll(caller.arg.getValue());
            }
        } else {
            map.put("__arg", caller.arg);
            map.put("__loop", caller.getLoopIndex());
            if (caller.arg != null && caller.arg.isMap()) {
                map.putAll(caller.arg.getValue());
            }
        }
        if (scenario.isOutlineExample() && !scenario.isDynamic()) { // init examples row magic variables
            Map<String, Object> exampleData = scenario.getExampleData();
            exampleData.forEach((k, v) -> map.put(k, v));
            map.put("__row", exampleData);
            map.put("__num", scenario.getExampleIndex());
            // TODO breaking change configure outlineVariablesAuto deprecated          
        }
        return map;
    }

    private void evalConfigJs(String js, String displayName) {
        if (js == null) {
            return;
        }
        Variable fun = engine.evalKarateExpression(js);
        if (!fun.isJsFunction()) {
            logger.warn("not a valid js function: {}", displayName);
            return;
        }
        try {
            Map<String, Object> map = engine.getOrEvalAsMap(fun);
            engine.setVariables(map);
        } catch (Exception e) {
            String message = scenario.getDebugInfo() + "\n" + displayName + "\n" + e.getMessage();
            error = new KarateException(message, e);
            stopped = true;
            configFailed = true;
        }
    }

    public boolean isSelectedForExecution() {
        Feature feature = scenario.getFeature();
        int callLine = feature.getCallLine();
        if (callLine != -1) {
            int sectionLine = scenario.getSection().getLine();
            int scenarioLine = scenario.getLine();
            if (callLine == sectionLine || callLine == scenarioLine) {
                logger.info("found scenario at line: {}", callLine);
                return true;
            }
            logger.trace("skipping scenario at line: {}, needed: {}", scenario.getLine(), callLine);
            return false;
        }
        String callName = feature.getCallName();
        if (callName != null) {
            if (scenario.getName().matches(callName)) {
                logger.info("found scenario at line: {} - {}", scenario.getLine(), callName);
                return true;
            }
            logger.trace("skipping scenario at line: {} - {}, needed: {}", scenario.getLine(), scenario.getName(), callName);
            return false;
        }
        String callTag = feature.getCallTag();
        if (callTag != null) {
            if (tags.contains(callTag)) {
                logger.info("scenario called at line: {} by tag: {}", scenario.getLine(), callTag);
                return true;
            }
            logger.trace("skipping scenario at line: {} with call by tag effective: {}", scenario.getLine(), callTag);
            return false;
        }
        if (caller.isNone()) {
            if (tags.evaluate(featureRuntime.suite.tagSelector)) {
                logger.trace("matched scenario at line: {} with tags effective: {}", scenario.getLine(), tags.getTags());
                return true;
            }
            logger.trace("skipping scenario at line: {} with tags effective: {}", scenario.getLine(), tags.getTags());
            return false;
        } else {
            return true; // when called, tags are ignored, all scenarios will be run
        }
    }

    //==========================================================================
    //
    public void beforeRun() {
        if (logAppender == null) { // not perf, not debug
            logAppender = LOG_APPENDER.get();
        }
        logger.setAppender(logAppender);
        if (resultAppender == null) { // top level (no caller)
            resultAppender = RESULT_APPENDER.get();
            resultAppender.append(scenario.getUniqueId() + "\n");
            resultAppender.collect(); // clean slate for this thread            
        }
        if (scenario.isDynamic()) {
            steps = scenario.getBackgroundSteps();
        } else {
            steps = background == null ? scenario.getStepsIncludingBackground() : scenario.getSteps();
        }
        ScenarioEngine.set(engine);
        engine.init();
        result.setExecutorName(Thread.currentThread().getName());
        result.setStartTime(System.currentTimeMillis() - featureRuntime.suite.startTime);
        if (!dryRun) {
            if (caller.isNone() && !caller.isKarateConfigDisabled()) {
                // evaluate config js, variables above will apply !
                evalConfigJs(featureRuntime.suite.karateBase, "karate-base.js");
                evalConfigJs(featureRuntime.suite.karateConfig, "karate-config.js");
                evalConfigJs(featureRuntime.suite.karateConfigEnv, "karate-config-" + featureRuntime.suite.env + ".js");
            }
            if (background == null) {
                featureRuntime.suite.hooks.forEach(h -> h.beforeScenario(this));
            }
        }
    }

    @Override
    public void run() {
        try { // make sure we call afterRun() even on crashes
            // and operate countdown latches, else we may hang the parallel runner
            if (steps == null) {
                beforeRun();
            }
            int count = steps.size();
            int index = 0;
            while ((index = nextStepIndex()) < count) {
                currentStep = steps.get(index);
                execute(currentStep);
                if (currentStepResult != null) { // can be null if debug step-back or hook skip
                    String json = JsonUtils.toJson(currentStepResult.toKarateJson());
                    resultAppender.append(json);
                    resultAppender.append(",\n");
                }
            }
            List<Map<String, Object>> list = Json.of("[" + resultAppender.collect() + "]").asList();
            list.forEach(m -> result.addStepResult(StepResult.fromKarateJson(featureRuntime.suite.workingDir, scenario, m)));
        } catch (Exception e) {
            logError("scenario [run] failed\n" + e.getMessage());
            currentStepResult = result.addFakeStepResult("scenario [run] failed", e);
        } finally {
            if (!scenario.isDynamic()) { // don't add "fake" scenario to feature results
                afterRun();
            }
        }
    }

    public void execute(Step step) {
        if (!stopped && !dryRun) {
            boolean shouldExecute = true;
            for (RuntimeHook hook : featureRuntime.suite.hooks) {
                if (!hook.beforeStep(step, this)) {
                    shouldExecute = false;
                }
            }
            if (!shouldExecute) {
                return;
            }
        }
        Result stepResult;
        final boolean executed = !stopped;
        if (stopped) {
            if (aborted && engine.getConfig().isAbortedStepsShouldPass()) {
                stepResult = Result.passed(0);
            } else if (configFailed) {
                stepResult = Result.failed(0, error, step);
            } else {
                stepResult = Result.skipped();
            }
        } else if (dryRun) {
            stepResult = Result.passed(0);
        } else {
            stepResult = StepRuntime.execute(step, actions);
        }
        currentStepResult = new StepResult(step, stepResult);
        if (stepResult.isAborted()) { // we log only aborts for visibility
            aborted = true;
            stopped = true;
            logger.debug("abort at {}", step.getDebugInfo());
        } else if (stepResult.isFailed()) {
            stopped = true;
            error = stepResult.getError();
            logError(error.getMessage());
        } else {
            boolean hidden = reportDisabled || (step.isPrefixStar() && !step.isPrint() && !engine.getConfig().isShowAllSteps());
            currentStepResult.setHidden(hidden);
        }
        addStepLogEmbedsAndCallResults();
        if (stepResult.isFailed()) {
            if (engine.driver != null) {
                engine.driver.onFailure(currentStepResult);
            }
            if (engine.robot != null) {
                engine.robot.onFailure(currentStepResult);
            }
        }
        if (executed && !dryRun) {
            featureRuntime.suite.hooks.forEach(h -> h.afterStep(currentStepResult, this));
        }
    }

    public void afterRun() {
        try {
            result.setEndTime(System.currentTimeMillis() - featureRuntime.suite.startTime);
            engine.logLastPerfEvent(result.getFailureMessageForDisplay());
            if (currentStepResult == null) {
                currentStepResult = result.addFakeStepResult("no steps executed", null);
            }
            if (!dryRun) {
                engine.invokeAfterHookIfConfigured(false);
                featureRuntime.suite.hooks.forEach(h -> h.afterScenario(this));
                engine.stop(currentStepResult);
            }
            addStepLogEmbedsAndCallResults();
        } catch (Exception e) {
            logError("scenario [cleanup] failed\n" + e.getMessage());
            currentStepResult = result.addFakeStepResult("scenario [cleanup] failed", e);
        }
    }

    private void addStepLogEmbedsAndCallResults() {
        boolean showLog = !reportDisabled && engine.getConfig().isShowLog();
        String stepLog = logAppender.collect();
        if (showLog) {
            currentStepResult.appendToStepLog(stepLog);
        }
        if (callResults != null) {
            currentStepResult.addCallResults(callResults);
            callResults = null;
        }
        if (embeds != null) {
            currentStepResult.addEmbeds(embeds);
            embeds = null;
        }
    }

    @Override
    public String toString() {
        return scenario.toString();
    }

}
