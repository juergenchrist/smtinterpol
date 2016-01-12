(set-option :produce-proofs true)
(set-option :proof-check-mode false)
(set-option :model-check-mode false)

(set-logic QF_UF)
(declare-fun a () Bool)

(assert (or a false))
(assert (not a))

(check-sat)
(set-option :print-terms-cse false)
(get-proof)