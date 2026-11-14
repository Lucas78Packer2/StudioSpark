package com.studiospark.app.stems
import kotlin.math.*
/** UMX-HQ host transform: periodic4096 Hann,1024hop,reflect-centered,stereo; no fixed EQ masks. */
object StemDsp{
 const val FFT=4096;const val HOP=1024;const val BINS=2049
 private val window=DoubleArray(FFT){(.5-.5*cos(2*PI*it/FFT)).toFloat().toDouble()}
 data class Spectrum(val count:Int,val samples:Int,val real:FloatArray,val imaginary:FloatArray,val magnitude:FloatArray)
 private fun transform(re:DoubleArray,im:DoubleArray,inverse:Boolean){val n=re.size;var j=0;for(i in 1 until n){var bit=n shr 1;while(j and bit!=0){j=j xor bit;bit=bit shr 1};j=j xor bit;if(i<j){val a=re[i];re[i]=re[j];re[j]=a;val b=im[i];im[i]=im[j];im[j]=b}}
  var len=2;while(len<=n){val ang=(if(inverse)2 else -2)*PI/len;val cr=cos(ang);val ci=sin(ang);var base=0;while(base<n){var wr=1.0;var wi=0.0;for(k in 0 until len/2){val a=base+k;val b=a+len/2;val tr=wr*re[b]-wi*im[b];val ti=wr*im[b]+wi*re[b];re[b]=re[a]-tr;im[b]=im[a]-ti;re[a]+=tr;im[a]+=ti;val next=wr*cr-wi*ci;wi=wr*ci+wi*cr;wr=next};base+=len};len*=2};if(inverse)for(i in re.indices){re[i]=re[i]/n.toDouble();im[i]=im[i]/n.toDouble()}
 }
 private fun reflect(index:Int,n:Int):Int{if(n<2)return 0;val period=2*(n-1);val q=Math.floorMod(index,period);return if(q<n)q else period-q}
 fun stft(stereo:FloatArray,cancel:()->Boolean={false}):Spectrum{require(stereo.isNotEmpty()&&stereo.size%2==0&&stereo.all{it.isFinite()});val n=stereo.size/2;val count=n/HOP+1;val re=FloatArray(2*BINS*count);val im=FloatArray(re.size);val mag=FloatArray(re.size);val r=DoubleArray(FFT);val q=DoubleArray(FFT)
  for(c in 0..1)for(t in 0 until count){if(cancel())throw InterruptedException("Separation cancelled");for(i in 0 until FFT){r[i]=stereo[reflect(t*HOP+i-FFT/2,n)*2+c]*window[i];q[i]=0.0};transform(r,q,false);for(b in 0 until BINS){val k=(c*BINS+b)*count+t;re[k]=r[b].toFloat();im[k]=q[b].toFloat();mag[k]=hypot(r[b],q[b]).toFloat()}}
  return Spectrum(count,n,re,im,mag)
 }
 fun inverse(s:Spectrum,mask:FloatArray,cancel:()->Boolean={false}):FloatArray{require(mask.size==s.real.size);val total=HOP*(s.count-1)+FFT;val norm=DoubleArray(total);for(t in 0 until s.count)for(i in 0 until FFT)norm[t*HOP+i]+=window[i]*window[i];val out=FloatArray(s.samples*2);val r=DoubleArray(FFT);val q=DoubleArray(FFT)
  for(c in 0..1){val accum=DoubleArray(total);for(t in 0 until s.count){if(cancel())throw InterruptedException("Separation cancelled");for(b in 0 until BINS){val k=(c*BINS+b)*s.count+t;r[b]=(s.real[k]*mask[k]).toDouble();q[b]=(s.imaginary[k]*mask[k]).toDouble()};for(b in BINS until FFT){r[b]=r[FFT-b];q[b]=-q[FFT-b]};transform(r,q,true);for(i in 0 until FFT)accum[t*HOP+i]+=r[i]*window[i]};for(i in 0 until s.samples){val k=i+FFT/2;out[i*2+c]=(accum[k]/max(norm[k],1e-12)).toFloat()}}
  return out
 }
 fun masks(estimates:List<FloatArray>):List<FloatArray>{require(estimates.size==4&&estimates.all{it.size==estimates[0].size&&it.all{v->v.isFinite()}});return List(4){FloatArray(estimates[0].size)}.also{out->for(i in estimates[0].indices){var sum=0f;for(s in 0..3)sum+=max(0f,estimates[s][i]);for(s in 0..3)out[s][i]=if(sum>1e-10f)max(0f,estimates[s][i])/sum else .25f}}}
}
