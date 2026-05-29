package org.example.coral.model;

/**
 * How the orchestrator should route a request after intent extraction.
 * READ      -> full read pipeline, no confirmation gate
 * MUTATE    -> read pipeline + policy gate, may require confirmation
 * REFLECT   -> productivity/reflection reasoning over existing context, no new Coral query
 * SMALLTALK -> skip planning/execution, reasoning only
 */
public enum ActionClass {
    READ,
    MUTATE,
    REFLECT,
    SMALLTALK
}
