(set-option :produce-proofs true)
(set-option :proof-check-mode true)

(set-logic QF_AX)
(declare-sort U 0)
(declare-fun i () U)
(declare-fun j () U)
(declare-fun k1 () U)
(declare-fun k2 () U)
(declare-fun k3 () U)
(declare-fun v1 () U)
(declare-fun v2 () U)
(declare-fun v3 () U)
(declare-fun a () (Array U U))
(declare-fun b () (Array U U))
(declare-fun s1 () (Array U U))
(declare-fun s2 () (Array U U))

(assert (! (and (not (= i k1)) (and (not (= i k2)) (= s1 (store s2 k2 v2)))) :named A))
(assert (! (and (not (= i k3)) (and (= a (store s1 k1 v1)) (and (= b (store s2 k3 v3)) 
(and (= i j) (not (= (select a i) (select b j))))))) :named B))

(check-sat)
(set-option :print-terms-cse false)
(get-proof)
(get-interpolants A B)
(exit)