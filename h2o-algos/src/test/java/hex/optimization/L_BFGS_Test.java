package hex.optimization;

import hex.FrameTask.DataInfo;
import hex.glm.GLM;
import hex.glm.GLM.GLMColBasedGradientSolver;
import hex.glm.GLM.GLMGradientInfo;
import hex.glm.GLM.GLMGradientSolver;
import hex.glm.GLMModel.GLMParameters;
import hex.glm.GLMModel.GLMParameters.Family;
import hex.optimization.L_BFGS.GradientInfo;
import hex.optimization.L_BFGS.GradientSolver;
import hex.optimization.L_BFGS.L_BFGS_Params;
import org.junit.BeforeClass;
import org.junit.Test;
import water.*;
import water.fvec.Frame;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
* Created by tomasnykodym on 9/16/14.
*/
public class L_BFGS_Test  extends TestUtil {
  @BeforeClass
  public static void setup() {
    stall_till_cloudsize(1);
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  // test on Rosenbrock's function (known optimum at (a,a^2), minimum = 0)
  @Test
  public void rosenbrock() {
    final double a = 1, b = 100;
    GradientSolver gs = new GradientSolver() {
      @Override
      public GradientInfo[] getGradient(double[][] betas) {
        double[][] grads = new double[betas.length][betas[0].length];
        double[] objs = new double[grads.length];
        for (int i = 0; i < grads.length; ++i) {
          final double[] g = grads[i] = grads[i].clone();
          final double x = betas[i][0];
          final double y = betas[i][1];
          final double xx = x * x;
          g[0] = -2 * a + 2 * x - 4 * b * (y * x - x * xx);
          g[1] = 2 * b * (y - xx);
          objs[i] = (a - x) * (a - x) + b * (y - xx) * (y - xx);
        }
        GradientInfo[] ginfos = new GradientInfo[betas.length];
        for (int i = 0; i < betas.length; ++i)
          ginfos[i] = new GradientInfo(objs[i], grads[i]);
        return ginfos;
      }
    };
    int fails = 0;
    int N = 1000;
    for (int i = 0; i < N; ++i) {
      L_BFGS_Params parms = new L_BFGS_Params();
      parms._stepDec = .9;
      parms._nBetas = 3;
      parms._minStep = 1e-18;
      L_BFGS.Result r = L_BFGS.solve(gs, parms, L_BFGS.startCoefs(2,987654321));
      if (Math.abs(r.ginfo._objVal) > 1e-4)
        ++fails;
    }
    assertTrue("too many fails " + fails, fails < 5);
  }

  @Test
  public void logistic() {
    Key parsedKey = Key.make("prostate");
    DataInfo dinfo = null;
    try {
      GLMParameters glmp = new GLMParameters(Family.binomial, Family.binomial.defaultLink);
      glmp._alpha = new double[]{0};
      glmp._lambda = new double[]{1e-5};
      Frame source = parse_test_file(parsedKey, "smalldata/glm_test/prostate_cat_replaced.csv");
      source.add("CAPSULE", source.remove("CAPSULE"));
      source.remove("ID").remove();
      Frame valid = new Frame(source._names.clone(),source.vecs().clone());
      dinfo = new DataInfo(Key.make(),source, valid, 1, false, DataInfo.TransformType.STANDARDIZE, DataInfo.TransformType.NONE);
      DKV.put(dinfo._key,dinfo);
      GLMGradientSolver solver = new GLMGradientSolver(glmp, dinfo, 1e-5,source.vec("CAPSULE").mean(), source.numRows());
      L_BFGS_Params lp = new L_BFGS_Params();
      lp._gradEps = 1e-8;
      double [] beta = MemoryManager.malloc8d(dinfo.fullN()+1);
      beta[beta.length-1] = glmp.link(source.vec("CAPSULE").mean());
      L_BFGS.Result r = L_BFGS.solve(solver, lp, beta);
      GLMGradientInfo ginfo = (GLMGradientInfo)r.ginfo;
      assertEquals(378.34, ginfo._val.residualDeviance(), 1e-1);
    } finally {
      if(dinfo != null)
        DKV.remove(dinfo._key);
      Value v = DKV.get(parsedKey);
      if (v != null) {
        v.<Frame>get().delete();
      }
    }
  }

  // Test LSM on arcene - wide dataset with ~10k columns
  @Test
  public void testArcene() {
    Key parsedKey = Key.make("arcene_parsed");
    DataInfo dinfo = null;
    try {
      Frame source = parse_test_file(parsedKey, "smalldata/glm_test/arcene.csv");
      Frame valid = new Frame(source._names.clone(),source.vecs().clone());
      GLMParameters glmp = new GLMParameters(Family.gaussian);
      glmp._lambda = new double[]{1e-5};
      dinfo = new DataInfo(Key.make(),source, valid, 1, false, DataInfo.TransformType.STANDARDIZE, DataInfo.TransformType.NONE);

      DKV.put(dinfo._key,dinfo);
      GradientSolver solver = new GLMColBasedGradientSolver(glmp, dinfo, 1e-5,source.lastVec().mean(), source.numRows());
      L_BFGS_Params lp = new L_BFGS_Params();
      lp._minStep = 1e-8;
      long t1 = System.currentTimeMillis();
      double [] beta = MemoryManager.malloc8d(dinfo.fullN()+1);
      beta[beta.length-1] = glmp.link(source.lastVec().mean());
      L_BFGS.solve(solver, lp, beta.clone());
      L_BFGS.Result r = L_BFGS.solve(solver, lp, beta.clone());
      long t2 = System.currentTimeMillis();
      GradientInfo ginfo = r.ginfo;
      assertEquals( .5 * glmp._lambda[0] * GLM.l2norm(r.coefs), ginfo._objVal, 1e-3);
      assertTrue("iter# expected < 100, got " + r.iter, r.iter < 100);
      lp = new L_BFGS_Params();
      lp._minStep = 1e-8;
      solver = new GLM.GLMGradientSolver(glmp,dinfo,1e-5,source.lastVec().mean(), source.numRows());
      L_BFGS.solve(solver, lp, beta.clone());
      L_BFGS.Result r2 = L_BFGS.solve(solver, lp, beta.clone());
      long t3 = System.currentTimeMillis();
      System.out.println("solver1 took " + (t2-t1) + "ms, objval = " + r.ginfo._objVal);
      System.out.println("solver2 took " + (t3-t2) + "ms, objval = " + r2.ginfo._objVal);
    } finally {
      if(dinfo != null)
        DKV.remove(dinfo._key);
      Value v = DKV.get(parsedKey);
      if (v != null) {
        v.<Frame>get().delete();
      }
    }
  }
}
