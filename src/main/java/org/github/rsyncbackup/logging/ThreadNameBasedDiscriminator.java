package org.github.rsyncbackup.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.sift.Discriminator;

/**
 * Code from http://www.nurkiewicz.com/2013/04/siftingappender-logging-different.html
 */
public class ThreadNameBasedDiscriminator implements Discriminator<ILoggingEvent>
{
    private static final String KEY = "threadName";
    private boolean started;

    @Override
    public String getDiscriminatingValue(ILoggingEvent iLoggingEvent)
    {
        return Thread.currentThread().getName();
    }

    @Override
    public String getKey()
    {
        return KEY;
    }

    public void start()
    {
        started = true;
    }

    public void stop()
    {
        started = false;
    }

    public boolean isStarted()
    {
        return started;
    }
}