/** Durable event identity, not text or arrival timestamp, defines deduplication. */
export function appendExecutionFact(events,event){
    if(events.some(e=>e.sourceRunId===event.sourceRunId&&e.sequence===event.sequence))return events;
    return [...events,event];
}
