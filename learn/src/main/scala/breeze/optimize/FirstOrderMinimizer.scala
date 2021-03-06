package breeze.optimize

import breeze.util.logging.{ConsoleLogging, Logged}
import breeze.math.{NormedVectorSpace, MutableCoordinateSpace}
import breeze.util.Implicits._

/**
 *
 * @param minImprovementWindow How many iterations to improve function by at least improvementTol
 * @author dlwh
 */
abstract class FirstOrderMinimizer[T,-DF<:StochasticDiffFunction[T]](maxIter: Int = -1,
                                                                     tolerance: Double=1E-5,
                                                                     improvementTol: Double=1E-3,
                                                                     val minImprovementWindow: Int = 10,
                                                                     val numberOfImprovementFailures: Int = 1)(implicit vspace: NormedVectorSpace[T, Double]) extends Minimizer[T,DF] with Logged {

  type History
  case class State(x: T,
                   value: Double, grad: T,
                   adjustedValue: Double, adjustedGradient: T,
                   iter: Int,
                   initialAdjVal: Double,
                   history: History,
                   fVals: IndexedSeq[Double] = IndexedSeq.empty,
                   numImprovementFailures: Int = 0,
                   searchFailed: Boolean = false) {
  }

  protected def initialHistory(f: DF, init: T): History
  protected def adjust(newX: T, newGrad: T, newVal: Double):(Double,T) = (newVal,newGrad)
  protected def chooseDescentDirection(state: State):T
  protected def determineStepSize(state: State, f: DF, direction: T):Double
  protected def takeStep(state: State, dir: T, stepSize:Double):T
  protected def updateHistory(newX: T, newGrad: T, newVal: Double, oldState: State):History
  /**
   * Take this many steps and then reset x to the original x. Mostly for Adagrad, which can behave oddly
   * until it has a good sense of rate of change.
   */
  protected def numDepthChargeSteps:Int = 0

  protected def updateFValWindow(oldState: State, newAdjVal: Double):IndexedSeq[Double] = {
    val interm = oldState.fVals :+ newAdjVal
    if(interm.length > minImprovementWindow) interm.drop(1)
    else interm
  }

  protected def initialState(f: DF, init: T) = {
    val x = init
    val (value,grad) = f.calculate(x)
    val (adjValue,adjGrad) = adjust(x,grad,value)
    val history = initialHistory(f,init)
    State(x,value,grad,adjValue,adjGrad,0,adjValue,history)
  }

  def iterations(f: DF,init: T): Iterator[State] = {
    var failedOnce = false
    val it = Iterator.iterate(doDepthCharge(f,init)) { state => try {
        val dir = chooseDescentDirection(state)
        val stepSize = determineStepSize(state, f, dir)
        log.info("Step Size:" + stepSize)
        val x = takeStep(state,dir,stepSize)
        val (value,grad) = f.calculate(x)
        log.info("Val and Grad Norm:" + value + " " + vspace.norm(grad))
        val (adjValue,adjGrad) = adjust(x,grad,value)
        log.info("Adj Val and Grad Norm:" + adjValue + " " + vspace.norm(adjGrad))
        val history = updateHistory(x,grad,value,state)
        val newAverage = updateFValWindow(state, adjValue)
        failedOnce = false
        var s = State(x,value,grad,adjValue,adjGrad,state.iter + 1, state.initialAdjVal, history, newAverage, 0)
        val improvementFailure = (state.fVals.length >= minImprovementWindow && state.fVals.nonEmpty && state.fVals.last > state.fVals.head * (1-improvementTol))
        if(improvementFailure)
          s = s.copy(fVals = IndexedSeq.empty, numImprovementFailures = state.numImprovementFailures + 1)
        s
    } catch {
      case x: FirstOrderException if !failedOnce =>
        failedOnce = true
        log.error("Failure! Resetting history: " + x)
        state.copy(history = initialHistory(f,state.x))
      case x: FirstOrderException =>
        log.error("Failure again! Giving up and returning. Maybe the objective is just poorly behaved?")
        state.copy(searchFailed = true)
    }
    }.takeUpToWhere ( state =>
      (state.iter >= maxIter && maxIter >= 0)
        || (vspace.norm(state.adjustedGradient) <= math.max(tolerance * state.initialAdjVal.abs,1E-8))
        || (state.numImprovementFailures >= numberOfImprovementFailures)
        || state.searchFailed
    )
    it:Iterator[State]
  }

  def minimize(f: DF, init: T):T = {
    iterations(f,init).last.x
  }

  private def doDepthCharge(f: DF, init: T): State = {
    var state = initialState(f, init)
    for(i <- 0 until numDepthChargeSteps) {
      val dir = chooseDescentDirection(state)
      val stepSize = determineStepSize(state, f, dir)
      val x = takeStep(state, dir, stepSize)
      val (value,grad) = f.calculate(x)
      val (adjValue,adjGrad) = adjust(x,grad,value)
      val history = updateHistory(x,grad,value,state)
      log.info("False Step %d: v=%f g=%f".format(i,adjValue, vspace.norm(adjGrad)))
      state = State(x, value, grad, adjValue, adjGrad, 0, state.initialAdjVal, history, IndexedSeq(), 0)
    }

    initialState(f, init).copy(history=state.history)
  }
}

sealed class FirstOrderException(msg: String="") extends RuntimeException(msg)
class NaNHistory extends FirstOrderException
class StepSizeUnderflow extends FirstOrderException
class LineSearchFailed(gradNorm: Double, dirNorm: Double) extends FirstOrderException("Grad norm: %.4f Dir Norm: %.4f".format(gradNorm, dirNorm))

object FirstOrderMinimizer {

  /**
   * OptParams is a Configuration-compatible case class that can be used to select optimization
   * routines at runtime.
   *
   * Configurations:
   * 1) useStochastic=false,useL1=false: LBFGS with L2 regularization
   * 2) useStochastic=false,useL1=true: OWLQN with L1 regularization
   * 3) useStochastic=true,useL1=false: AdaptiveGradientDescent with L2 regularization
   * 3) useStochastic=true,useL1=true: AdaptiveGradientDescent with L1 regularization
   *
   *
   * @param batchSize size of batches to use if useStochastic and you give a BatchDiffFunction
   * @param regularization regularization constant to use.
   * @param alpha rate of change to use, only applies to SGD.
   * @param maxIterations, how many iterations to do.
   * @param useL1 if true, use L1 regularization. Otherwise, use L2.
   * @param tolerance convergence tolerance, looking at both average improvement and the norm of the gradient.
   * @param useStochastic if false, use LBFGS or OWLQN. If true, use some variant of Stochastic Gradient Descent.
   */
  case class OptParams(batchSize:Int = 512,
                       regularization: Double = 1.0,
                       alpha: Double = 0.5,
                       maxIterations:Int = 1000,
                       useL1: Boolean = false,
                       tolerance:Double = 1E-3,
                       useStochastic: Boolean= false) {
    def minimize[T](f: BatchDiffFunction[T], init: T)(implicit arith: MutableCoordinateSpace[T, Double]): T = {
      this.iterations(f, init).last.x
    }

    def iterations[T](f: BatchDiffFunction[T], init: T)(implicit arith: MutableCoordinateSpace[T, Double]): Iterator[FirstOrderMinimizer[T, BatchDiffFunction[T]]#State] = {
      val it = if(useStochastic) {
         this.iterations(f.withRandomBatches(batchSize), init)(arith)
      } else {
        iterations(f:DiffFunction[T], init)
      }

      it.asInstanceOf[Iterator[FirstOrderMinimizer[T, BatchDiffFunction[T]]#State]]
    }

    def iterations[T](f: StochasticDiffFunction[T], init:T)(implicit arith: MutableCoordinateSpace[T, Double]):Iterator[FirstOrderMinimizer[T, StochasticDiffFunction[T]]#State] = {
      val r = if(useL1) {
        new AdaptiveGradientDescent.L1Regularization[T](regularization, eta=alpha, maxIter = maxIterations)(arith) {
        }
      } else { // L2
        new StochasticGradientDescent[T](alpha,  maxIterations)(arith) with AdaptiveGradientDescent.L2Regularization[T] {
          override val lambda = regularization
        }
      }
      r.iterations(f,init)
    }

    def iterations[T](f: DiffFunction[T], init:T)(implicit vspace: MutableCoordinateSpace[T, Double]): Iterator[LBFGS[T]#State] = {
       if(useL1) new OWLQN[T](maxIterations, 5, regularization, tolerance)(vspace).iterations(f,init)
      else (new LBFGS[T](maxIterations, 5, tolerance=tolerance)(vspace)).iterations(DiffFunction.withL2Regularization(f,regularization),init)
    }
  }
}
