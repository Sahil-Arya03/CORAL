package org.example.coral.service;

import org.example.coral.model.ActionClass;
import org.example.coral.model.IntentResult;
import org.springframework.stereotype.Service;

/**
 * Deterministic routing decision. Trusts the extracted intent's class but guards against an
 * unactionable mutation (MUTATE with no actionType) by downgrading it to a READ.
 */
@Service
public class ActionClassificationService {

    public ActionClass classify(IntentResult intent) {
        if (intent == null || intent.actionClass() == null) {
            return ActionClass.READ;
        }
        if (intent.actionClass() == ActionClass.MUTATE && intent.actionType() == null) {
            return ActionClass.READ;
        }
        return intent.actionClass();
    }
}
