package project

import java.util.Properties

import org.apache.spark.sql.SparkSession

object SqlApp {
  def main(args: Array[String]): Unit = {
    System.setProperty("HADOOP_USER_NAME", "root")
    val spark = SparkSession
      .builder()
      .master("local[*]")
      .appName("SqlApp")
      .enableHiveSupport()
      .getOrCreate()

    spark.udf.register("remark",new CityRemarkUDAF)

    //去执行sql，从hive查询数据
    spark.sql("use spark1602")
    spark.sql(
      """
        |select
        |    ci.*,
        |    pi.product_name,
        |    uva.click_product_id
        |from user_visit_action uva
        |join product_info pi
        |on uva.click_product_id = pi.product_id
        |join city_info ci
        |on uva.city_id = ci.city_id
        |""".stripMargin).createOrReplaceTempView("t1")

    spark.sql(
      """
        |select
        |   area,
        |   product_name,
        |   count(*) count,
        |   remark(city_name) remark
        |from t1
        |group by area,product_name
        |""".stripMargin).createOrReplaceTempView("t2")

    spark.sql(
      """
        |select
        |   area,
        |   product_name,
        |   count,
        |   remark,
        |   rank() over(partition by area order by count desc) rk
        |from t2
        |""".stripMargin).createOrReplaceTempView("t3")

    val url = "jdbc:mysql://hadoop102:3306/sparksql?useUnicode=true&characterEncoding=UTF-8"
    val user = "root"
    val pw = "root"

    val props = new Properties()
    props.put("user",user)
    props.put("password",pw)

    spark.sql(
      """
        |select
        |  area,
        |   product_name,
        |   count,
        |   remark
        |from t3
        |where rk<=3
        |""".stripMargin)
      .coalesce(1)
        .write
      .mode("overwrite")
    .jdbc(url,"sql1602",props)
    //把结果写到mysql中


    spark.close()

  }

}



/*
各区域热门商品 Top3
这里的热门商品是从点击量的维度来看的.
计算各个区域前三大热门商品，并备注上每个商品在主要城市中的分布比例，超过两个城市用其他显示。
例如:
地区	商品名称		点击次数	城市备注
华北	商品A		100000	北京21.2%，天津13.2%，其他65.6%
华北	商品P		80200	北京63.0%，太原10%，其他27.0%
华北	商品M		40000	北京63.0%，太原10%，其他27.0%
东北	商品J		92000	大连28%，辽宁17.0%，其他 55.0%

---
自定义聚合函数
    聚合函数，只需要接受一个城市名
    每收到一个城市，可以给这个城市统计一次


-------
user_visit_action   product_info  city_info
1.先把需要的字段查出来   t1
select
    ci.*,
    pi.product_name,
    uva.click_product_id
from user_visit_action uva
join product_info pi
on uva.click_product_id = pi.product_id
join city_info ci
on uva.city_id = ci.city_id

2.按照地区和商品名称聚合  t2
select
   area,
   product_name,
   count(*) count
from t1
group by area,product_name

3.按照地区进行分组开窗，排序 开窗函数   t3   (rank(1 2 2 4 5...) row_number(1 2 3 4)  dense_rank(1 2 2 3 4))
select
   area,
   product_name,
   count,
   rank() over(partition by area order by count desc) rk
from t2

4.过滤出来名次小于等于3的
select
  area,
   product_name,
   count,
from t3
where rk<=3

*/