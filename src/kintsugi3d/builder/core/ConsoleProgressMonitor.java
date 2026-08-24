/*
 * Copyright (c) 2019 - 2026 Seth Berrier, Michael Tetzlaff, Jacob Buelow, Luke Denney, Ian Anderson, Zoe Cuthrell, Blane Suess, Isaac Tesch, Nathaniel Willius, Atlas Collins, Simon Cao
 * Copyright (c) 2019 The Regents of the University of Minnesota
 *
 * Licensed under GPLv3
 * ( http://www.gnu.org/licenses/gpl-3.0.html )
 *
 * This code is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * This code is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 */

package kintsugi3d.builder.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A progress monitor for non-GUI (headless) use. Stage/completion milestones go through SLF4J like any other
 * log message, but per-item progress (setProgress(), potentially called hundreds of times per stage) is
 * rendered as a single console line that repaints itself in place via a carriage return, instead of one log
 * line per call - log4j2's Console appender always terminates a logged line with a newline, so that rendering
 * happens through a direct System.out write rather than through the logger.
 */
public class ConsoleProgressMonitor implements ProgressMonitor
{
    private static final Logger LOG = LoggerFactory.getLogger(ConsoleProgressMonitor.class);
    private static final int BAR_WIDTH = 30;

    private String processName = "Process";
    private int stageCount;
    private double maxProgress;
    private int lastLineLength = 0;

    @Override
    public void allowUserCancellation() throws UserCancellationException
    {
    }

    @Override
    public void cancelComplete(UserCancellationException e)
    {
        endBar();
        LOG.info("{} cancelled.", processName);
    }

    @Override
    public void start()
    {
        LOG.info("{} started.", processName);
    }

    @Override
    public void setProcessName(String processName)
    {
        this.processName = processName;
    }

    @Override
    public void setStageCount(int count)
    {
        this.stageCount = count;
    }

    @Override
    public void setStage(int stage, String message)
    {
        endBar();
        maxProgress = 0;

        if (stageCount > 0)
        {
            LOG.info("[Stage {}/{}] {}", stage, stageCount, message);
        }
        else
        {
            LOG.info("[Stage {}] {}", stage, message);
        }
    }

    @Override
    public void advanceStage(String message)
    {
        endBar();
        maxProgress = 0;
        LOG.info("{}", message);
    }

    @Override
    public void setMaxProgress(double maxProgress)
    {
        this.maxProgress = maxProgress;
    }

    @Override
    public void setProgress(double progress, String message)
    {
        if (maxProgress <= 0)
        {
            endBar();
            LOG.info("{}", message);
            return;
        }

        double fraction = Math.max(0, Math.min(1, progress / maxProgress));
        int filled = (int) Math.round(fraction * BAR_WIDTH);
        String bar = "=".repeat(filled) + " ".repeat(BAR_WIDTH - filled);
        printBarLine(String.format("[%s] %3d%% %s", bar, Math.round(fraction * 100), message));
    }

    @Override
    public void complete()
    {
        endBar();
        LOG.info("{} complete.", processName);
    }

    @Override
    public void fail(Throwable e)
    {
        endBar();
        LOG.error("{} failed.", processName, e);
    }

    @Override
    public void warn(Throwable e)
    {
        endBar();
        LOG.warn("{} encountered a recoverable error.", processName, e);
    }

    @Override
    public boolean isConflictingProcess()
    {
        return false;
    }

    private void printBarLine(String line)
    {
        StringBuilder padded = new StringBuilder(line);
        while (padded.length() < lastLineLength)
        {
            padded.append(' ');
        }

        System.out.print('\r' + padded.toString());
        System.out.flush();
        lastLineLength = line.length();
    }

    private void endBar()
    {
        if (lastLineLength > 0)
        {
            System.out.println();
            lastLineLength = 0;
        }
    }
}
