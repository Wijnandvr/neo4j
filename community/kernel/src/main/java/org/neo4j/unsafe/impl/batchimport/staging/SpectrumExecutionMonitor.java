/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.unsafe.impl.batchimport.staging;

import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

import org.neo4j.unsafe.impl.batchimport.stats.DetailLevel;
import org.neo4j.unsafe.impl.batchimport.stats.Keys;
import org.neo4j.unsafe.impl.batchimport.stats.StatsProvider;
import org.neo4j.unsafe.impl.batchimport.stats.StepStats;

import static java.lang.Math.pow;
import static java.lang.Math.round;
import static java.lang.String.format;

import static org.neo4j.helpers.Format.duration;

/**
 * This is supposed to be a beautiful one-line {@link ExecutionMonitor}, looking like:
 *
 * <pre>
 * NODE |--INPUT--|--NODE--|======NODE=PROPERTY======|-------------WRITER-------------| 1000
 * </pre>
 *
 * where there's one line per stage, updated rapidly, overwriting the line each time. The width
 * of the {@link Step} column is based on how slow it is compared to the others.
 *
 * The width of the "spectrum" is user specified, but is dynamic in that it can shrink or expand
 * based on how many simultaneous {@link StageExecution executions} this monitor is monitoring.
 *
 * The specified width is included stage identifier and progress, so in a console the whole
 * console width can be specified.
 */
public class SpectrumExecutionMonitor extends PollingExecutionMonitor
{
    public static final int DEFAULT_WIDTH = 80;
    private static final char[] WEIGHTS = new char[] {'k', 'M', 'B', 'T'};

    private final PrintStream out;
    private final int width;

    public SpectrumExecutionMonitor( long interval, TimeUnit unit, PrintStream out, int tapeWidth )
    {
        super( interval, unit );
        this.out = out;
        this.width = tapeWidth;
    }

    @Override
    protected void start( StageExecution[] executions )
    {
        for ( int i = 0; i < executions.length; i++ )
        {
            if ( i > 0 )
            {
                out.print( ", " );
            }
            out.print( executions[i].getStageName() );
        }
        out.println();
    }

    @Override
    protected void end( StageExecution[] executions, long totalTimeMillis )
    {
        out.println();
    }

    @Override
    protected void poll( StageExecution[] executions )
    {
        int active = countActive( executions );
        float partWidth = (float) width / active;
        StringBuilder builder = new StringBuilder();
        boolean allPrinted = true;
        for ( StageExecution execution : executions )
        {
            if ( execution.stillExecuting() )
            {
                allPrinted &= printSpectrum( builder, execution, round( partWidth ) );
            }
        }
        if ( allPrinted )
        {
            out.print( "\r" + builder );
        }
    }

    private int countActive( StageExecution[] executions )
    {
        int active = 0;
        for ( StageExecution execution : executions )
        {
            if ( execution.stillExecuting() )
            {
                active++;
            }
        }
        return active;
    }

    private boolean printSpectrum( StringBuilder builder, StageExecution execution, int width )
    {
        long[] values = values( execution );
        long total = total( values );
        if ( total == 0 )
        {
            return false;
        }

        // reduce the width with the known extra characters we know we'll print in and around the spectrum
        width -= values.length + 1/*'|' chars*/ + 4 /*progress chars*/;

        QuantizedProjection projection = new QuantizedProjection( total, width );
        long lastDoneBatches = 0;
        int stepIndex = 0;
        for ( StepStats step : execution.stats() )
        {
            if ( !projection.next( avg( step ) ) )
            {
                throw new IllegalStateException();
            }
            long stepWidth = projection.step();
            String name = step.toString( DetailLevel.IMPORTANT );
            builder.append( stepIndex++ == 0 ? '[' : '|' );
            int charIndex = 0; // negative value "delays" the text, i.e. pushes it to the right
            char backgroundChar = '-';
            for ( int i = 0; i < stepWidth; i++, charIndex++ )
            {
                char ch = backgroundChar;
                if ( charIndex >= 0 && charIndex < name.length() && charIndex < stepWidth )
                {
                    ch = name.charAt( charIndex );
                }
                builder.append( ch );
            }
            lastDoneBatches = step.stat( Keys.done_batches ).asLong();
        }
        builder.append( "]" ).append( fitInFour( lastDoneBatches ) );
        return true;
    }

    private static String fitInFour( long value )
    {
        int weight = weight( value );

        String result = weight == 0
                ? String.valueOf( value )
                : format( "%.1f%s", value / pow( 1000, weight ), WEIGHTS[weight] );
        return pad( result, 4, ' ' );
    }

    private static String pad( String result, int length, char padChar )
    {
        while ( result.length() < length )
        {
            result = padChar + result;
        }
        return result;
    }

    private static int weight( long value )
    {
        int weight = 0;
        while ( value >= 1000 )
        {
            value /= 1000;
            weight++;
        }
        return weight;
    }

    private long[] values( StageExecution execution )
    {
        long[] values = new long[execution.size()];
        int i = 0;
        for ( StepStats stats : execution.stats() )
        {
            values[i++] = avg( stats );
        }
        return values;
    }

    private long total( long[] values )
    {
        long total = 0;
        for ( long value : values )
        {
            total += value;
        }
        return total;
    }

    private long avg( StatsProvider step )
    {
        return step.stat( Keys.avg_processing_time ).asLong();
    }

    @Override
    public void done( long totalTimeMillis )
    {
        out.println();
        out.println( "IMPORT DONE. Took: " + duration( totalTimeMillis ) );
    }
}
