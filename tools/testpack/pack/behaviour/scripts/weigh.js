// Work a debugger stops in and a profiler times: `/scriptevent "tp:weigh" <count>` adds up the first
// <count> square roots. The line marked below is where the debugger game test sets its breakpoint.

export function weighTheProbe(count) {
    let total = 0;
    for (let i = 1; i <= count; i++) {
        total += Math.sqrt(i); // tp:weigh breakpoint
    }
    return total;
}
